package cl.franciscosolis.metalcraft.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cl.franciscosolis.metalcraft.NativeLoader;
import cl.franciscosolis.metalcraft.mtl.Mtl;
import cl.franciscosolis.metalcraft.shader.ShaderTranslator.Stage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Translates every vanilla core/post shader to MSL and checks the Metal runtime compiler accepts the result. */
class ShaderTranslatorTest {
    private static final Pattern IMPORT = Pattern.compile("^#moj_import <minecraft:(.+)>$", Pattern.MULTILINE);

    @Test
    void translatesAllVanillaShaders() throws Exception {
        NativeLoader.load();
        List<String> failures = new ArrayList<>();
        int count = 0;
        for (String dir : List.of("core", "post")) {
            for (String file : list(dir)) {
                if (!file.endsWith(".vsh") && !file.endsWith(".fsh")) continue;
                String name = dir + "/" + file;
                boolean vertex = file.endsWith(".vsh");
                try {
                    Map<String, Integer> varyings = Map.of();
                    String vsh = name.replace(".fsh", ".vsh");
                    if (!vertex && exists(vsh)) {
                        varyings = ShaderTranslator.translate(vsh, load(vsh), Stage.VERTEX, Map.of()).outputs();
                    }
                    ShaderTranslator.Result r = ShaderTranslator.translate(name, load(name), vertex ? Stage.VERTEX : Stage.FRAGMENT, varyings);
                    try {
                        Mtl.release(Mtl.newLibrary(r.msl()));
                    } catch (IllegalStateException e) {
                        failures.add(name + " (metal): " + e.getMessage() + "\n" + r.msl());
                    }
                    count++;
                } catch (ShaderTranslator.TranslationException e) {
                    failures.add(e.getMessage());
                }
            }
        }
        assertTrue(count > 70, "expected all vanilla shaders, found " + count);
        assertEquals(List.of(), failures, String.join("\n\n", failures));
    }

    @Test
    void reflectsResourcesByName() throws Exception {
        ShaderTranslator.Result vs = ShaderTranslator.translate("terrain.vsh", load("core/terrain.vsh"), Stage.VERTEX, Map.of());
        assertTrue(vs.inputs().keySet().containsAll(Set.of("Position", "Color", "UV0", "UV2", "Normal")), vs.inputs().toString());
        assertTrue(vs.buffers().containsKey("ChunkSection"), vs.buffers().toString());
        assertTrue(vs.samplers().containsKey("Sampler2"), vs.samplers().toString());

        ShaderTranslator.Result fs = ShaderTranslator.translate("terrain.fsh", load("core/terrain.fsh"), Stage.FRAGMENT, vs.outputs());
        for (var e : fs.inputs().entrySet()) assertEquals(vs.outputs().get(e.getKey()), e.getValue(), "varying " + e.getKey());
    }

    @Test
    void translatesSodiumChunkShaders() throws Exception {
        NativeLoader.load();
        // Mirrors Sodium's ShaderChunkRenderer: bound attribute locations and the defines it compiles with.
        Map<String, Integer> attributes = Map.of("a_Position", 0, "a_Color", 1, "a_TexCoord", 2, "a_LightAndData", 3);
        String defines = "#define USE_VERTEX_COMPRESSION\n#define USE_FOG\n#define USE_FRAGMENT_DISCARD\n";
        ShaderTranslator.Result vs = ShaderTranslator.translate("sodium.vsh", sodium("blocks/block_layer_opaque.vsh", defines), Stage.VERTEX, attributes);
        ShaderTranslator.Result fs = ShaderTranslator.translate("sodium.fsh", sodium("blocks/block_layer_opaque.fsh", defines), Stage.FRAGMENT, vs.outputs());
        Mtl.release(Mtl.newLibrary(vs.msl()));
        Mtl.release(Mtl.newLibrary(fs.msl()));

        assertEquals(attributes, vs.inputs());
        assertTrue(vs.defaults().keySet().containsAll(Set.of("u_ModelViewMatrix", "u_ProjectionMatrix", "u_RegionOffset", "u_CurrentTime")), vs.defaults().toString());
        assertTrue(fs.defaults().keySet().containsAll(Set.of("u_FogColor", "u_TexelSize", "u_UseRGSS")), fs.defaults().toString());
        assertTrue(vs.buffers().containsKey(ShaderTranslator.DEFAULT_BLOCK) && vs.buffers().containsKey("ChunkData"), vs.buffers().toString());
        assertTrue(fs.samplers().containsKey("u_BlockTex") && vs.samplers().containsKey("u_LightTex"));
    }

    private static final Pattern SODIUM_IMPORT = Pattern.compile("^#import <sodium:(.+)>$", Pattern.MULTILINE);

    private static String sodium(String name, String defines) throws IOException {
        String src = read("/assets/sodium/shaders/" + name);
        Matcher m = SODIUM_IMPORT.matcher(src);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(read("/assets/sodium/shaders/" + m.group(1))));
        return m.appendTail(sb).toString().replaceFirst("(#version [^\\n]*\\n)", "$1" + defines);
    }

    // Defines normally supplied by the RenderPipeline.
    private static final Map<String, String> DEFINES = Map.of("core/rendertype_end_portal.fsh", "#define PORTAL_LAYERS 15\n");

    private static String load(String name) throws IOException {
        String src = resolveImports(read("/assets/minecraft/shaders/" + name), new HashSet<>());
        return src.replaceFirst("(#version \\d+\\s*\n)", "$1" + DEFINES.getOrDefault(name, ""));
    }

    // ponytail: mirrors Minecraft's #moj_import handling closely enough for vanilla shaders; the real game hands us already-preprocessed source.
    private static String resolveImports(String src, Set<String> seen) throws IOException {
        Matcher m = IMPORT.matcher(src);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inc = "";
            if (seen.add(m.group(1))) {
                inc = resolveImports(read("/assets/minecraft/shaders/include/" + m.group(1)), seen).replaceFirst("#version \\d+", "");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(inc));
        }
        return m.appendTail(sb).toString();
    }

    private static boolean exists(String name) {
        return ShaderTranslatorTest.class.getResource("/assets/minecraft/shaders/" + name) != null;
    }

    private static String read(String path) throws IOException {
        try (InputStream in = ShaderTranslatorTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> list(String dir) throws Exception {
        // Vanilla assets live inside the Minecraft client jar on the test classpath.
        var uri = ShaderTranslatorTest.class.getResource("/assets/minecraft/shaders/core/terrain.vsh").toURI();
        java.nio.file.FileSystem fs;
        try {
            fs = java.nio.file.FileSystems.newFileSystem(uri, Map.of());
        } catch (java.nio.file.FileSystemAlreadyExistsException e) {
            fs = java.nio.file.FileSystems.getFileSystem(uri);
        }
        try (var files = Files.list(fs.getPath("/assets/minecraft/shaders/" + dir))) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
