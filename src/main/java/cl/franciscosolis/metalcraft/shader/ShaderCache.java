package cl.franciscosolis.metalcraft.shader;

import cl.franciscosolis.metalcraft.MetalCraft;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * On-disk cache of GLSL -> MSL translations. Translating every shader takes ~2 s per launch; Metal already caches the
 * MSL compilation itself. Keys include the translator's own bytecode, so changing the translation invalidates entries.
 */
final class ShaderCache {
    private static @Nullable Path directory;
    private static final byte[] TRANSLATOR_VERSION = translatorVersion();

    private ShaderCache() {}

    /** Enables the cache. Until called (e.g. in unit tests) every lookup misses and nothing is written. */
    static void enable(Path dir) {
        directory = dir;
    }

    static String key(String glsl, ShaderTranslator.Stage stage, Map<String, Integer> inputLocations) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(TRANSLATOR_VERSION);
            sha.update((byte) stage.ordinal());
            sha.update(new TreeMap<>(inputLocations).toString().getBytes(StandardCharsets.UTF_8));
            sha.update(glsl.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ShaderTranslator.@Nullable Result load(String key) {
        if (directory == null) return null;
        Path file = directory.resolve(key);
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            return new ShaderTranslator.Result(readString(in), readString(in), readMap(in), readMap(in), readMap(in), readMap(in), readMap(in),
                    readMap(in), in.readInt());
        } catch (IOException e) {
            MetalCraft.LOGGER.warn("Ignoring unreadable shader cache entry {}: {}", file, e.toString());
            return null;
        }
    }

    static void store(String key, ShaderTranslator.Result r) {
        if (directory == null) return;
        try {
            Files.createDirectories(directory);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writeString(out, r.msl());
                writeString(out, r.entryPoint());
                for (Map<String, Integer> m : java.util.List.of(r.buffers(), r.textures(), r.samplers(), r.inputs(), r.outputs(), r.defaults())) writeMap(out, m);
                out.writeInt(r.defaultsSize());
            }
            // Write-then-rename so a crash never leaves a truncated entry behind.
            Path tmp = Files.createTempFile(directory, key, ".tmp");
            Files.write(tmp, bytes.toByteArray());
            Files.move(tmp, directory.resolve(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            MetalCraft.LOGGER.warn("Couldn't write shader cache entry: {}", e.toString());
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }

    private static void writeMap(DataOutputStream out, Map<String, Integer> map) throws IOException {
        out.writeInt(map.size());
        for (var e : map.entrySet()) {
            writeString(out, e.getKey());
            out.writeInt(e.getValue());
        }
    }

    private static Map<String, Integer> readMap(DataInputStream in) throws IOException {
        int n = in.readInt();
        Map<String, Integer> map = new HashMap<>(n);
        for (int i = 0; i < n; i++) map.put(readString(in), in.readInt());
        return map;
    }

    private static byte[] translatorVersion() {
        try (InputStream in = ShaderTranslator.class.getResourceAsStream("ShaderTranslator.class")) {
            return in != null ? MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()) : new byte[0];
        } catch (IOException | NoSuchAlgorithmException e) {
            return new byte[0];
        }
    }
}
