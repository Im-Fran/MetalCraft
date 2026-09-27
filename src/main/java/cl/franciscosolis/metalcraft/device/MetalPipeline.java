package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.MetalCraft;
import cl.franciscosolis.metalcraft.mtl.Mtl;
import cl.franciscosolis.metalcraft.shader.ShaderTranslator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.LogicOp;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A RenderPipeline compiled to Metal. The MTLRenderPipelineState depends on the attachment formats, so variants are built lazily. */
public class MetalPipeline implements CompiledRenderPipeline {
    final RenderPipeline info;
    final ShaderTranslator.@Nullable Result vertex;
    final ShaderTranslator.@Nullable Result fragment;
    private final long vertexFn, fragmentFn;
    final long depthState;
    final int primitive;
    final int cull;
    private final Int2LongOpenHashMap variants = new Int2LongOpenHashMap();
    private boolean loggedLogicOp;

    private MetalPipeline(RenderPipeline info, ShaderTranslator.@Nullable Result vertex, ShaderTranslator.@Nullable Result fragment, long vertexFn, long fragmentFn) {
        this.info = info;
        this.vertex = vertex;
        this.fragment = fragment;
        this.vertexFn = vertexFn;
        this.fragmentFn = fragmentFn;
        DepthTestFunction depth = info.getDepthTestFunction();
        // GL disables depth writes along with the depth test.
        this.depthState = Mtl.newDepthStencilState(compare(depth), depth != DepthTestFunction.NO_DEPTH_TEST && info.isWriteDepth());
        this.primitive = primitive(info.getVertexFormatMode());
        this.cull = info.isCull() ? Mtl.CULL_BACK : Mtl.CULL_NONE;
    }

    static MetalPipeline compile(RenderPipeline info, ShaderSource source) {
        String name = info.getLocation().toString();
        try {
            String vsh = source.get(info.getVertexShader(), ShaderType.VERTEX);
            String fsh = source.get(info.getFragmentShader(), ShaderType.FRAGMENT);
            if (vsh == null || fsh == null) {
                MetalCraft.LOGGER.error("Couldn't find shader source for pipeline {}", name);
                return invalid(info);
            }
            var vs = ShaderTranslator.translate(info.getVertexShader() + ".vsh", GlslPreprocessor.injectDefines(vsh, info.getShaderDefines()),
                    ShaderTranslator.Stage.VERTEX, Map.of());
            var fs = ShaderTranslator.translate(info.getFragmentShader() + ".fsh", GlslPreprocessor.injectDefines(fsh, info.getShaderDefines()),
                    ShaderTranslator.Stage.FRAGMENT, vs.outputs());
            return new MetalPipeline(info, vs, fs, function(vs), function(fs));
        } catch (ShaderTranslator.TranslationException | IllegalStateException e) {
            MetalCraft.LOGGER.error("Couldn't compile pipeline {}: {}", name, e.getMessage());
            return invalid(info);
        }
    }

    private static MetalPipeline invalid(RenderPipeline info) {
        return new MetalPipeline(info, null, null, 0, 0);
    }

    // ponytail: one MTLLibrary per stage; a disk cache of compiled libraries (MTLBinaryArchive) is the upgrade if startup time matters.
    private static long function(ShaderTranslator.Result r) {
        long library = Mtl.newLibrary(r.msl());
        long fn = Mtl.newFunction(library, r.entryPoint());
        Mtl.release(library);
        return fn;
    }

    @Override
    public boolean isValid() {
        return vertexFn != 0;
    }

    /** Pipeline state for a pass with the given color format ordinal and depth format ordinal (-1 = no depth). */
    long state(int colorFormat, int depthFormat) {
        int key = colorFormat * 8 + depthFormat + 1;
        if (variants.containsKey(key)) return variants.get(key);
        long pso;
        try {
            pso = build(colorFormat, depthFormat);
        } catch (IllegalStateException e) {
            MetalCraft.LOGGER.error("Couldn't create Metal pipeline state for {}: {}", info.getLocation(), e.getMessage());
            pso = 0;
        }
        variants.put(key, pso);
        return pso;
    }

    /** Format of a texel-buffer uniform (samplerBuffer), or null if the name isn't one. */
    @Nullable TextureFormat texelFormat(String name) {
        for (RenderPipeline.UniformDescription u : info.getUniforms()) {
            if (u.name().equals(name) && u.type() == UniformType.TEXEL_BUFFER) return u.textureFormat();
        }
        return null;
    }

    private long build(int colorFormat, int depthFormat) {
        VertexFormat format = info.getVertexFormat();
        IntArrayList attribs = new IntArrayList();
        IntArrayList missing = new IntArrayList();
        Map<String, Integer> inputs = vertex.inputs();
        var names = format.getElementAttributeNames();
        var elements = format.getElements();
        for (int i = 0; i < elements.size(); i++) {
            Integer location = inputs.get(names.get(i));
            if (location == null) continue;
            VertexFormatElement e = elements.get(i);
            attribs.add(location);
            attribs.add(vertexFormat(e));
            attribs.add(format.getOffset(e));
        }
        for (var input : inputs.entrySet()) {
            if (!names.contains(input.getKey())) missing.add(input.getValue().intValue());
        }

        if (info.getColorLogic() != LogicOp.NONE && !loggedLogicOp) {
            // ponytail: Metal has no logic ops and no vanilla pipeline uses one; only mods could. Emulating OR_REVERSE
            // exactly would need programmable blending (framebuffer fetch in the fragment shader).
            MetalCraft.LOGGER.warn("Pipeline {} uses logic op {}, which Metal doesn't support; ignoring", info.getLocation(), info.getColorLogic());
            loggedLogicOp = true;
        }

        BlendFunction blend = info.getBlendFunction().orElse(null);
        int writeMask = (info.isWriteColor() ? 0b1110 : 0) | (info.isWriteAlpha() ? 0b0001 : 0);
        return Mtl.newRenderPipeline(vertexFn, fragmentFn, colorFormat, depthFormat,
                blend != null,
                blend != null ? blendFactor(blend.sourceColor().name()) : 0, blend != null ? blendFactor(blend.destColor().name()) : 0,
                blend != null ? blendFactor(blend.sourceAlpha().name()) : 0, blend != null ? blendFactor(blend.destAlpha().name()) : 0,
                writeMask, attribs.toIntArray(), missing.toIntArray(), format.getVertexSize(),
                ShaderTranslator.VERTEX_BUFFER_INDEX, info.getLocation().toString());
    }

    void close() {
        for (long pso : variants.values()) Mtl.release(pso);
        variants.clear();
        Mtl.release(vertexFn);
        Mtl.release(fragmentFn);
        Mtl.release(depthState);
    }

    public static int compare(DepthTestFunction f) {
        return switch (f) {
            case NO_DEPTH_TEST -> Mtl.COMPARE_ALWAYS;
            case EQUAL_DEPTH_TEST -> Mtl.COMPARE_EQUAL;
            case LEQUAL_DEPTH_TEST -> Mtl.COMPARE_LESS_EQUAL;
            case LESS_DEPTH_TEST -> Mtl.COMPARE_LESS;
            case GREATER_DEPTH_TEST -> Mtl.COMPARE_GREATER;
        };
    }

    private static int primitive(VertexFormat.Mode mode) {
        return switch (mode) {
            // Minecraft expands LINES and QUADS into triangles itself (shader-side widening / quad index buffer).
            case LINES, TRIANGLES, QUADS, TRIANGLE_FAN -> Mtl.PRIMITIVE_TRIANGLE;
            case DEBUG_LINES -> Mtl.PRIMITIVE_LINE;
            case DEBUG_LINE_STRIP -> Mtl.PRIMITIVE_LINE_STRIP;
            case POINTS -> Mtl.PRIMITIVE_POINT;
            case TRIANGLE_STRIP -> Mtl.PRIMITIVE_TRIANGLE_STRIP;
        };
    }

    /** SourceFactor and DestFactor share constant names; map both to MTLBlendFactor. */
    public static int blendFactor(String name) {
        return switch (name) {
            case "ZERO" -> 0;
            case "ONE" -> 1;
            case "SRC_COLOR" -> 2;
            case "ONE_MINUS_SRC_COLOR" -> 3;
            case "SRC_ALPHA" -> 4;
            case "ONE_MINUS_SRC_ALPHA" -> 5;
            case "DST_COLOR" -> 6;
            case "ONE_MINUS_DST_COLOR" -> 7;
            case "DST_ALPHA" -> 8;
            case "ONE_MINUS_DST_ALPHA" -> 9;
            case "SRC_ALPHA_SATURATE" -> 10;
            case "CONSTANT_COLOR" -> 11;
            case "ONE_MINUS_CONSTANT_COLOR" -> 12;
            case "CONSTANT_ALPHA" -> 13;
            case "ONE_MINUS_CONSTANT_ALPHA" -> 14;
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** Matches Blaze3D's GL attribute setup: COLOR/NORMAL normalized, integer UVs stay integer, everything else float. */
    private static int vertexFormat(VertexFormatElement e) {
        int n = e.count() - 1;
        boolean normalized = e.usage() == VertexFormatElement.Usage.COLOR || e.usage() == VertexFormatElement.Usage.NORMAL;
        return switch (e.type()) {
            case FLOAT -> 28 + n;
            case UBYTE -> (normalized ? new int[]{47, 7, 8, 9} : new int[]{45, 1, 2, 3})[n];
            case BYTE -> (normalized ? new int[]{48, 10, 11, 12} : new int[]{46, 4, 5, 6})[n];
            case USHORT -> (normalized ? new int[]{51, 19, 20, 21} : new int[]{49, 13, 14, 15})[n];
            case SHORT -> (normalized ? new int[]{52, 22, 23, 24} : new int[]{50, 16, 17, 18})[n];
            case UINT -> 36 + n;
            case INT -> 32 + n;
        };
    }
}
