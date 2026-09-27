package cl.franciscosolis.metalcraft.shader;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * Translates Minecraft's GLSL 330 shaders into Metal Shading Language.
 *
 * GLSL -> SPIR-V (shaderc, OpenGL semantics) -> MSL (SPIRV-Cross). Clip space is fixed up in the vertex
 * stage (Y flipped so render targets keep GL's bottom-up memory layout, Z remapped from [-1,1] to [0,1]).
 * Resources are looked up by name afterwards, so nothing in the GLSL source needs rewriting.
 */
public final class ShaderTranslator {
    /** Metal buffer index used for the vertex stream; stays clear of the automatically assigned UBO indices. */
    public static final int VERTEX_BUFFER_INDEX = 30;

    private static final Pattern MSL_RESERVED = Pattern.compile("\\b(sampler)\\b");
    private static final int SPV_DECORATION_LOCATION = 30;
    private static final int MSL_VERSION_2_4 = 20400;

    private ShaderTranslator() {}

    public enum Stage { VERTEX, FRAGMENT }

    /**
     * @param msl          Metal source; its entry point is {@link #entryPoint}
     * @param buffers      uniform block name -> [[buffer(n)]]
     * @param textures     sampler / texel-buffer name -> [[texture(n)]]; the matching [[sampler(n)]] uses the same key in {@link #samplers}
     * @param samplers     sampler name -> [[sampler(n)]] (absent for texel buffers)
     * @param inputs       stage input name -> location (vertex: attribute index)
     * @param outputs      stage output name -> location
     * @param defaults     loose (default-block) uniform name -> byte offset inside the {@link #DEFAULT_BLOCK} buffer
     * @param defaultsSize size in bytes of the {@link #DEFAULT_BLOCK} buffer (0 if the stage has no loose uniforms)
     */
    public record Result(String msl, String entryPoint, Map<String, Integer> buffers, Map<String, Integer> textures,
                         Map<String, Integer> samplers, Map<String, Integer> inputs, Map<String, Integer> outputs,
                         Map<String, Integer> defaults, int defaultsSize) {}

    /** Loose uniforms (`uniform vec3 foo;`) have no Metal equivalent; they're gathered into this std140 block. */
    public static final String DEFAULT_BLOCK = "MetalCraftDefaults";
    private static final Pattern LOOSE_UNIFORM = Pattern.compile(
            "^\\s*uniform\\s+((?:[biu]?vec[234])|(?:mat[234])|bool|int|uint|float)\\s+(\\w+)\\s*;.*$", Pattern.MULTILINE);
    private static final Pattern VERSION = Pattern.compile("^#version[^\\n]*\\n", Pattern.MULTILINE);

    public static final class TranslationException extends Exception {
        public TranslationException(String message) {
            super(message);
        }
    }

    /**
     * @param glsl             fully preprocessed source (imports resolved, defines injected)
     * @param inputLocations   for fragment shaders: the vertex stage's {@link Result#outputs()}, so varyings match by name
     */
    public static Result translate(String name, String glsl, Stage stage, Map<String, Integer> inputLocations) throws TranslationException {
        String key = ShaderCache.key(glsl, stage, inputLocations);
        Result cached = ShaderCache.load(key);
        if (cached != null) return cached;
        Result result = translateUncached(name, glsl, stage, inputLocations);
        ShaderCache.store(key, result);
        return result;
    }

    /** Stores translations under {@code directory} so later launches skip shaderc/SPIRV-Cross. */
    public static void enableCache(java.nio.file.Path directory) {
        ShaderCache.enable(directory);
    }

    private static Result translateUncached(String name, String glsl, Stage stage, Map<String, Integer> inputLocations) throws TranslationException {
        // ponytail: `sampler` is a legal GLSL identifier but a type in MSL, and SPIRV-Cross keeps the name. Extend if other clashes show up.
        ByteBuffer spirv = compileSpirv(name, gatherLooseUniforms(MSL_RESERVED.matcher(glsl).replaceAll("$1_")), stage);
        try {
            return toMsl(name, spirv, stage, inputLocations);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(spirv);
        }
    }

    /** Moves loose uniforms into a {@link #DEFAULT_BLOCK} uniform block; members keep their names, so shader code is unchanged. */
    static String gatherLooseUniforms(String glsl) {
        Matcher m = LOOSE_UNIFORM.matcher(glsl);
        StringBuilder members = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        while (m.find()) {
            members.append("    ").append(m.group(1)).append(' ').append(m.group(2)).append(";\n");
            m.appendReplacement(rest, "");
        }
        if (members.isEmpty()) return glsl;
        m.appendTail(rest);
        String block = "layout(std140) uniform " + DEFAULT_BLOCK + " {\n" + members + "};\n";
        Matcher v = VERSION.matcher(rest);
        return v.find() ? rest.insert(v.end(), block).toString() : block + rest;
    }

    private static ByteBuffer compileSpirv(String name, String glsl, Stage stage) throws TranslationException {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_opengl, shaderc_env_version_opengl_4_5);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_auto_map_locations(options, true);
            int kind = stage == Stage.VERTEX ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader;
            long result = shaderc_compile_into_spv(compiler, glsl, kind, name, "main", options);
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new TranslationException(name + ": " + shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = shaderc_result_get_bytes(result);
                ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc(bytes.remaining());
                copy.put(bytes).flip();
                return copy;
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private static Result toMsl(String name, ByteBuffer spirv, Stage stage, Map<String, Integer> inputLocations) throws TranslationException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            check(spvc_context_create(pp), 0, name);
            long ctx = pp.get(0);
            try {
                check(spvc_context_parse_spirv(ctx, spirv.asIntBuffer(), spirv.remaining() / 4, pp), ctx, name);
                long ir = pp.get(0);
                check(spvc_context_create_compiler(ctx, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_COPY, pp), ctx, name);
                long compiler = pp.get(0);

                check(spvc_compiler_create_compiler_options(compiler, pp), ctx, name);
                long opts = pp.get(0);
                spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_2_4);
                spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
                spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
                if (stage == Stage.VERTEX) {
                    spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
                    spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, true);
                    spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, true);
                }
                check(spvc_compiler_install_compiler_options(compiler, opts), ctx, name);

                check(spvc_compiler_create_shader_resources(compiler, pp), ctx, name);
                long resources = pp.get(0);

                Map<String, Integer> inputs = new HashMap<>();
                forEach(resources, SPVC_RESOURCE_TYPE_STAGE_INPUT, stack, r -> {
                    Integer location = inputLocations.get(r.nameString());
                    if (location != null) spvc_compiler_set_decoration(compiler, r.id(), SPV_DECORATION_LOCATION, location);
                    inputs.put(r.nameString(), spvc_compiler_get_decoration(compiler, r.id(), SPV_DECORATION_LOCATION));
                });
                Map<String, Integer> outputs = new HashMap<>();
                forEach(resources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, stack,
                        r -> outputs.put(r.nameString(), spvc_compiler_get_decoration(compiler, r.id(), SPV_DECORATION_LOCATION)));

                check(spvc_compiler_compile(compiler, pp), ctx, name);
                String msl = org.lwjgl.system.MemoryUtil.memUTF8(pp.get(0));

                // Automatic MSL bindings are only known after compile(); resources the compiler dropped as unused report -1.
                Map<String, Integer> buffers = new HashMap<>();
                forEach(resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stack,
                        r -> putBinding(buffers, blockName(compiler, r), spvc_compiler_msl_get_automatic_resource_binding(compiler, r.id())));
                Map<String, Integer> textures = new HashMap<>();
                Map<String, Integer> samplers = new HashMap<>();
                forEach(resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stack, r -> {
                    putBinding(textures, r.nameString(), spvc_compiler_msl_get_automatic_resource_binding(compiler, r.id()));
                    // Texel buffers (samplerBuffer) have no sampler object in MSL.
                    putBinding(samplers, r.nameString(), spvc_compiler_msl_get_automatic_resource_binding_secondary(compiler, r.id()));
                });
                forEach(resources, SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, stack,
                        r -> putBinding(textures, r.nameString(), spvc_compiler_msl_get_automatic_resource_binding(compiler, r.id())));

                Map<String, Integer> defaults = new HashMap<>();
                int[] defaultsSize = {0};
                forEach(resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stack, r -> {
                    if (!DEFAULT_BLOCK.equals(blockName(compiler, r))) return;
                    long type = spvc_compiler_get_type_handle(compiler, r.base_type_id());
                    var offset = stack.mallocInt(1);
                    for (int i = 0; i < spvc_type_get_num_member_types(type); i++) {
                        spvc_compiler_type_struct_member_offset(compiler, type, i, offset);
                        defaults.put(spvc_compiler_get_member_name(compiler, r.base_type_id(), i), offset.get(0));
                    }
                    var size = stack.mallocPointer(1);
                    spvc_compiler_get_declared_struct_size(compiler, type, size);
                    defaultsSize[0] = (int) ((size.get(0) + 15) & ~15L);  // MSL pads structs to their 16-byte alignment
                });

                String entry = spvc_compiler_get_cleansed_entry_point_name(compiler, "main",
                        stage == Stage.VERTEX ? 0 /* SpvExecutionModelVertex */ : 4 /* SpvExecutionModelFragment */);
                return new Result(msl, entry, buffers, textures, samplers, inputs, outputs, defaults, defaultsSize[0]);
            } finally {
                spvc_context_destroy(ctx);
            }
        }
    }

    private static void putBinding(Map<String, Integer> map, String name, int index) {
        if (index >= 0) map.put(name, index);
    }

    /** Minecraft's uniform blocks are instance-less, so the variable name is empty and the block type name is what the pipeline refers to. */
    private static String blockName(long compiler, SpvcReflectedResource r) {
        String typeName = spvc_compiler_get_name(compiler, r.base_type_id());
        return typeName == null || typeName.isEmpty() ? r.nameString() : typeName;
    }

    private static void forEach(long resources, int type, MemoryStack stack, java.util.function.Consumer<SpvcReflectedResource> action) {
        PointerBuffer list = stack.mallocPointer(1);
        PointerBuffer count = stack.mallocPointer(1);
        spvc_resources_get_resource_list_for_type(resources, type, list, count);
        if (count.get(0) == 0) return;
        SpvcReflectedResource.Buffer buf = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
        for (SpvcReflectedResource r : buf) action.accept(r);
    }

    private static void check(int result, long ctx, String name) throws TranslationException {
        if (result != SPVC_SUCCESS) {
            throw new TranslationException(name + ": SPIRV-Cross error " + result + (ctx != 0 ? ": " + spvc_context_get_last_error_string(ctx) : ""));
        }
    }
}
