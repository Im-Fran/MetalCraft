package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** A shared-storage MTLBuffer: CPU and GPU see the same memory, so mapping is a pointer, not a copy. */
public class MetalBuffer extends GpuBuffer {
    /** Buffers up to this size are rewritten by swapping in fresh storage (GL-style orphaning) instead of a GPU blit. */
    private static final long ORPHAN_LIMIT = 256 * 1024;

    long handle;
    private long contents;
    private long texelView;
    private long lastGpuWrite;
    private boolean closed;

    MetalBuffer(int usage, long size) {
        super(usage, size);
        allocate();
    }

    private void allocate() {
        // MSL rounds uniform structs up to their 16-byte alignment, while std140 sizes from Blaze3D are exact.
        handle = Mtl.newBuffer((size() + 15) & ~15L);
        if (handle == 0) throw new com.mojang.blaze3d.GpuOutOfMemoryException("Could not allocate buffer of " + size());
        contents = Mtl.bufferContents(handle);
    }

    /** Records that queued GPU work writes into this buffer, so the CPU-side contents are stale until it completes. */
    void markGpuWrite() {
        lastGpuWrite = Mtl.fence();
    }

    /**
     * Writes {@code length} bytes at {@code offset} without a blit, when possible: the buffer gets new storage (commands
     * already recorded keep reading the old one) holding the old contents plus the new bytes. Returns false if the
     * buffer is too big to copy cheaply or the GPU may still be writing it, in which case the caller must blit.
     */
    boolean tryOrphanWrite(long offset, long source, long length) {
        if (size() > ORPHAN_LIMIT || lastGpuWrite > Mtl.completedFence()) return false;
        long oldHandle = handle, oldContents = contents;
        allocate();
        if (offset > 0 || length < size()) MemoryUtil.memCopy(oldContents, contents, size());
        MemoryUtil.memCopy(source, contents + offset, length);
        Mtl.release(oldHandle);
        Mtl.release(texelView);
        texelView = 0;
        return true;
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
