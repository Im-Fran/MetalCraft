package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

public class MetalTexture extends GpuTexture {
    final long handle;
    /** GL tolerates mip chains longer than the texture supports (extra levels are 0x0); Metal doesn't, so the real chain is clamped. */
    final int metalMips;
    private boolean closed;

    MetalTexture(int usage, String label, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        boolean cube = (usage & USAGE_CUBEMAP_COMPATIBLE) != 0;
        this.metalMips = Math.min(mipLevels, 32 - Integer.numberOfLeadingZeros(Math.max(width, height)));
        this.handle = Mtl.newTexture(format.ordinal(), width, height, metalMips, cube, (usage & USAGE_RENDER_ATTACHMENT) != 0, label);
        if (handle == 0) throw new com.mojang.blaze3d.GpuOutOfMemoryException("Could not allocate texture of " + width + "x" + height + " for " + label);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Mtl.release(handle);
    }
}
