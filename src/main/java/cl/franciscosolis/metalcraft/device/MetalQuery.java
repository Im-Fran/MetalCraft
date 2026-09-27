package cl.franciscosolis.metalcraft.device;

import com.mojang.blaze3d.systems.GpuQuery;
import java.util.OptionalLong;

// ponytail: GPU timer queries are only used by the debug profiler; wire up MTLCounterSampleBuffer if that's needed.
enum MetalQuery implements GpuQuery {
    INSTANCE;

    @Override
    public OptionalLong getValue() {
        return OptionalLong.empty();
    }

    @Override
    public void close() {}
}
