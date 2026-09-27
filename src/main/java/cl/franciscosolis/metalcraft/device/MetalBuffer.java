package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** A shared-storage MTLBuffer: CPU and GPU see the same memory, so mapping is a pointer, not a copy. */
public class MetalBuffer extends GpuBuffer {
    final long handle;
    private final long contents;
    private long texelView;
    private boolean closed;

    MetalBuffer(int usage, long size) {
        super(usage, size);
        // MSL rounds uniform structs up to their 16-byte alignment, while std140 sizes from Blaze3D are exact.
        this.handle = Mtl.newBuffer((size + 15) & ~15L);
        if (handle == 0) throw new com.mojang.blaze3d.GpuOutOfMemoryException("Could not allocate buffer of " + size);
        this.contents = Mtl.bufferContents(handle);
    }

    ByteBuffer view(long offset, long length) {
        return MemoryUtil.memByteBuffer(contents + offset, (int) length);
    }

    long address() {
        return contents;
    }

    /** Texture view used when this buffer is bound as a samplerBuffer (texel buffer). */
    long texelView(TextureFormat format) {
        if (texelView == 0) texelView = Mtl.newTextureBuffer(handle, format.ordinal(), size(), format.pixelSize());
        return texelView;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Command buffers retain what they reference, so releasing while the GPU still uses it is safe.
        Mtl.release(texelView);
        Mtl.release(handle);
    }
}
