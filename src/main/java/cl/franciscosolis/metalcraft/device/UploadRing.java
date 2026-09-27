package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import java.util.ArrayDeque;

/**
 * Staging memory for CPU -> GPU copies. Large shared MTLBuffers are sub-allocated linearly and recycled once the GPU
 * has finished the commands that read them, instead of allocating an MTLBuffer per upload. Render thread only.
 */
public final class UploadRing {
    private static final long CHUNK_SIZE = 4L << 20;
    private static final long ALIGNMENT = 16;

    /** Write {@code length} bytes at {@code address}, then encode a blit reading {@code buffer} at {@code offset}. */
    public record Slice(long buffer, long offset, long address) {}

    private static final class Chunk {
        final long handle, contents, capacity;
        long offset, lastUse;

        Chunk(long capacity) {
            this.capacity = capacity;
            this.handle = Mtl.newBuffer(capacity);
            this.contents = Mtl.bufferContents(handle);
        }
    }

    private static final ArrayDeque<Chunk> RETIRED = new ArrayDeque<>();
    private static Chunk current;

    private UploadRing() {}

    public static Slice reserve(long length) {
        Chunk chunk = chunkWithRoom(length);
        long offset = chunk.offset;
        chunk.offset = (offset + length + ALIGNMENT - 1) & -ALIGNMENT;
        chunk.lastUse = Mtl.fence();  // Reusable once the command buffer recording this upload completes.
        return new Slice(chunk.handle, offset, chunk.contents + offset);
    }

    private static Chunk chunkWithRoom(long length) {
        if (current != null && current.offset + length <= current.capacity) return current;
        if (current != null) RETIRED.addLast(current);
        Chunk oldest = RETIRED.peekFirst();
        if (oldest != null && oldest.capacity >= length && oldest.lastUse <= Mtl.completedFence()) {
            RETIRED.removeFirst();
            oldest.offset = 0;
            current = oldest;
        } else {
            // ponytail: chunks are never freed; steady state is ~3 frames of uploads. Trim RETIRED if memory matters.
            current = new Chunk(Math.max(CHUNK_SIZE, length));
        }
        return current;
    }
}
