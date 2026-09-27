package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import com.mojang.blaze3d.systems.GpuQuery;
import java.util.OptionalLong;

/**
 * GPU time between timerQueryBegin and timerQueryEnd: the summed execution time of the command buffers submitted in
 * between (Minecraft brackets a whole frame, for F3's GPU utilization).
 */
final class MetalQuery implements GpuQuery {
    private final long firstFence;
    private long lastFence = -1;

    MetalQuery() {
        this.firstFence = Mtl.fence();
    }

    void end() {
        lastFence = Mtl.fence();
    }

    @Override
    public OptionalLong getValue() {
        if (lastFence < 0) return OptionalLong.empty();
        long nanos = Mtl.gpuNanosBetween(firstFence, lastFence);
        return nanos < 0 ? OptionalLong.empty() : OptionalLong.of(nanos);
    }

    @Override
    public void close() {}
}
