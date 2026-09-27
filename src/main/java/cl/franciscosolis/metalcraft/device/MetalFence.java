package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import com.mojang.blaze3d.buffers.GpuFence;
import java.util.concurrent.TimeUnit;

/** Completion of everything submitted before it, tracked with an MTLSharedEvent value. */
record MetalFence(long value) implements GpuFence {
    @Override
    public boolean awaitCompletion(long timeoutNanos) {
        return Mtl.fenceWait(value, timeoutNanos == Long.MAX_VALUE ? Long.MAX_VALUE : TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
    }

    @Override
    public void close() {}
}
