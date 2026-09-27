package cl.franciscosolis.metalcraft;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetalCraft implements ClientModInitializer {
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
                LOGGER.info("[{}] fps={}", DISABLED ? "OpenGL" : "Metal", mc.getFps());
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), msg -> LOGGER.info(msg.getString()));
            });
        }
    }
}
