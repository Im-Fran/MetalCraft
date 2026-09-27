package cl.franciscosolis.metalcraft;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetalCraft implements ClientModInitializer {
    /** Logs per-pass GPU milliseconds per frame, most expensive first. */
    private static void logGpuProfile(double frames) {
        StringBuilder sb = new StringBuilder("GPU ms/frame by pass (passes/frame):");
        java.util.Map<String, Double> values = new java.util.HashMap<>();
        Mtl.takeGpuProfile().lines().map(l -> l.split("=", 2)).forEach(e -> values.put(e[0], Double.parseDouble(e[1])));
        values.entrySet().stream()
                .filter(e -> !e.getKey().endsWith(" #"))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> sb.append(String.format("%n  %7.3f  (%5.1f)  %s", e.getValue() * 1000 / Math.max(1, frames),
                        values.getOrDefault(e.getKey() + " #", 0.0) / Math.max(1, frames), e.getKey())));
        LOGGER.info(sb.toString());
    }

    public static final String MOD_ID = "metalcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** -Dmetalcraft.disable=true keeps vanilla OpenGL, for A/B benchmarks with the same build. */
    public static final boolean DISABLED = Boolean.getBoolean("metalcraft.disable");

    @Override
    public void onInitializeClient() {
        if (DISABLED) LOGGER.warn("MetalCraft disabled by -Dmetalcraft.disable, using OpenGL");
        NativeLoader.load();
        LOGGER.info("MetalCraft native loaded, Metal device: {}", Mtl.deviceName());

        // Dev aid: -Dmetalcraft.autoScreenshot=<ticks> saves a screenshot through the Metal readback path, no OS permissions needed.
        int shotTick = Integer.getInteger("metalcraft.autoScreenshot", -1);
        if (shotTick >= 0) {
            int[] ticks = {0};
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (++ticks[0] % shotTick != 0) return;
                if (DISABLED) {
                    LOGGER.info("[OpenGL] fps={}", mc.getFps());
                } else {
                    // GPU time since the last report, spread over the frames rendered meanwhile.
                    double gpuMs = Mtl.takeGpuSeconds() * 1000 / Math.max(1, mc.getFps() * shotTick / 20.0);
                    LOGGER.info("[Metal] fps={} gpuMsPerFrame={}", mc.getFps(), String.format("%.3f", gpuMs));
                    if (Mtl.GPU_PROFILING) logGpuProfile(mc.getFps() * shotTick / 20.0);
                }
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), msg -> LOGGER.info(msg.getString()));
            });
        }
    }
}
