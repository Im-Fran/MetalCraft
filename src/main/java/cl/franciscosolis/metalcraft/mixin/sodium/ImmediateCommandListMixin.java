package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import java.nio.ByteBuffer;
import net.caffeinemc.mods.sodium.client.gl.array.GlVertexArray;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapping;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferStorageFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferTarget;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlImmutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlVertexArrayTessellation;
import net.caffeinemc.mods.sodium.client.gl.tessellation.TessellationBinding;
import net.caffeinemc.mods.sodium.client.gl.util.EnumBitField;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Sodium's GL command list, reimplemented on Metal buffers. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice$ImmediateCommandList", remap = false)
abstract class ImmediateCommandListMixin {
    /** @author MetalCraft @reason vertex arrays are stateless on Metal */
    @Overwrite
    public void bindVertexArray(GlVertexArray array) {}

    /** @author MetalCraft @reason vertex arrays are stateless on Metal */
    @Overwrite
    public void unbindVertexArray() {}

    /** @author MetalCraft @reason there are no GL binding points; buffers are passed explicitly */
    @Overwrite
    public void bindBuffer(GlBufferTarget target, GlBuffer buffer) {}

    /** @author MetalCraft @reason glBufferData -> fresh MTLBuffer */
    @Overwrite
    public void uploadData(GlMutableBuffer buffer, ByteBuffer data, GlBufferUsage usage) {
        SodiumMetal.allocate(buffer.handle(), data.remaining(), MemoryUtil.memAddress(data));
        buffer.setSize(data.remaining());
    }

    /** @author MetalCraft @reason glBufferSubData -> staged blit */
    @Overwrite
    public void uploadDataToOffset(GlMutableBuffer buffer, int offset, long pointer, int size) {
        SodiumMetal.subData(buffer.handle(), offset, pointer, size);
    }

    /** @author MetalCraft @reason glCopyBufferSubData -> blit */
    @Overwrite
    public void copyBufferSubData(GlBuffer src, GlBuffer dst, long readOffset, long writeOffset, long bytes) {
        SodiumMetal.copy(src.handle(), dst.handle(), readOffset, writeOffset, bytes);
    }

    /** @author MetalCraft @reason glBufferData(size) -> fresh MTLBuffer */
    @Overwrite
    public void allocateStorage(GlMutableBuffer buffer, long size, GlBufferUsage usage) {
        SodiumMetal.allocate(buffer.handle(), size, 0);
        buffer.setSize(size);
    }

    /** @author MetalCraft @reason immutable storage is the same shared MTLBuffer */
    @Overwrite
    public GlImmutableBuffer createImmutableBuffer(long size, EnumBitField<GlBufferStorageFlags> flags) {
        GlImmutableBuffer buffer = new GlImmutableBuffer(flags);
        SodiumMetal.allocate(buffer.handle(), size, 0);
        return buffer;
    }

    /** @author MetalCraft @reason release the MTLBuffer */
    @Overwrite
    public void deleteBuffer(GlBuffer buffer) {
        buffer.setActiveMapping(null);
        int handle = buffer.handle();
        buffer.invalidateHandle();
        SodiumMetal.deleteBuffer(handle);
    }

    /** @author MetalCraft @reason vertex arrays are stateless on Metal */
    @Overwrite
    public void deleteVertexArray(GlVertexArray array) {
        array.invalidateHandle();
    }

    /** @author MetalCraft @reason shared storage maps to the buffer contents directly */
    @Overwrite
    public GlBufferMapping mapBuffer(GlBuffer buffer, long offset, long length, EnumBitField<GlBufferMapFlags> flags) {
        if (buffer.getActiveMapping() != null) throw new IllegalStateException("Buffer is already mapped");
        GlBufferMapping mapping = new GlBufferMapping(buffer, SodiumMetal.map(buffer.handle(), offset, length));
        buffer.setActiveMapping(mapping);
        return mapping;
    }

    /** @author MetalCraft @reason nothing to unmap with shared storage */
    @Overwrite
    public void unmap(GlBufferMapping map) {
        map.getBufferObject().setActiveMapping(null);
        map.dispose();
    }

    /** @author MetalCraft @reason shared storage is coherent */
    @Overwrite
    public void flushMappedRange(GlBufferMapping map, int offset, int length) {}

    /** @author MetalCraft @reason vertex attribute setup happens when the pipeline state is built */
    @Overwrite
    public GlTessellation createTessellation(GlPrimitiveType primitiveType, TessellationBinding[] bindings) {
        return new GlVertexArrayTessellation(new GlVertexArray(), primitiveType, bindings);
    }
}
