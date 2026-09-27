package cl.franciscosolis.metalcraft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class NativeLoader {
    private static boolean loaded;

    private NativeLoader() {}

    public static synchronized void load() {
        if (loaded) return;
        try (InputStream in = NativeLoader.class.getResourceAsStream("/natives/libmetalcraft.dylib")) {
            if (in == null) throw new IllegalStateException("libmetalcraft.dylib missing from jar");
            // ponytail: fresh temp copy per launch, avoids stale-cache bugs; hash-named cache if startup time matters.
            Path lib = Files.createTempFile("metalcraft", ".dylib");
            lib.toFile().deleteOnExit();
            Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
            System.load(lib.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract MetalCraft native library", e);
        }
    }
}
