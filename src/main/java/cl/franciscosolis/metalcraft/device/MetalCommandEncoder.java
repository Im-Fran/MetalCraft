package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Records Blaze3D commands into the current MTLCommandBuffer. Unlike GL, nothing executes until the command buffer is
 * committed (on present or fence), so every CPU-side update to a resource that queued GPU work may read goes through a
 * staging buffer + blit to keep GL's in-order semantics.
 */
public class MetalCommandEncoder implements CommandEncoder {
    final MetalDevice device;
    private boolean inRenderPass;
    private long noDepthState;

    MetalCommandEncoder(MetalDevice device) {
        this.device = device;
    }

    long noDepthState() {
        if (noDepthState == 0) noDepthState = Mtl.newDepthStencilState(Mtl.COMPARE_ALWAYS, false);
        return noDepthState;
    }

    private void assertNoRenderPass() {
        if (inRenderPass) throw new IllegalStateException("Close the existing render pass before performing additional commands");
    }

    @Override
    public RenderPass createRenderPass(Supplier<String> label, GpuTextureView color, OptionalInt clearColor) {
        return createRenderPass(label, color, clearColor, null, OptionalDouble.empty());
    }

    @Override
    public RenderPass createRenderPass(Supplier<String> label, GpuTextureView color, OptionalInt clearColor, @Nullable GpuTextureView depth, OptionalDouble clearDepth) {
        assertNoRenderPass();
        if (color.isClosed()) throw new IllegalStateException("Color texture is closed");
        if ((color.texture().usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) throw new IllegalStateException("Color texture must have USAGE_RENDER_ATTACHMENT");
        if (depth != null && depth.isClosed()) throw new IllegalStateException("Depth texture is closed");
        int c = clearColor.orElse(0);
        if (Mtl.GPU_PROFILING) Mtl.setPassLabel(label.get());
        Mtl.beginPass(((MetalTextureView) color).handle, clearColor.isPresent(), ARGB.redFloat(c), ARGB.greenFloat(c), ARGB.blueFloat(c), ARGB.alphaFloat(c),
                depth != null ? ((MetalTextureView) depth).handle : 0, depth != null && clearDepth.isPresent(), clearDepth.orElse(1.0));
        inRenderPass = true;
        return new MetalRenderPass(this, color, depth);
    }

    void finishRenderPass() {
        Mtl.endPass();
        inRenderPass = false;
    }

    @Override
    public void clearColorTexture(GpuTexture texture, int color) {
        if (Mtl.GPU_PROFILING) Mtl.setPassLabel("Clear " + describe(texture));
        assertNoRenderPass();
        Mtl.beginPass(handle(texture), true, ARGB.redFloat(color), ARGB.greenFloat(color), ARGB.blueFloat(color), ARGB.alphaFloat(color), 0, false, 0);
        Mtl.endPass();
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture color, int c, GpuTexture depth, double depthValue) {
        if (Mtl.GPU_PROFILING) Mtl.setPassLabel("Clear " + describe(color) + " + depth");
        assertNoRenderPass();
        Mtl.beginPass(handle(color), true, ARGB.redFloat(c), ARGB.greenFloat(c), ARGB.blueFloat(c), ARGB.alphaFloat(c), handle(depth), true, depthValue);
        Mtl.endPass();
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture color, int c, GpuTexture depth, double depthValue, int x, int y, int w, int h) {
        if (Mtl.GPU_PROFILING) Mtl.setPassLabel("Clear region " + describe(color));
        assertNoRenderPass();
        Mtl.clearRegion(handle(color), color.getFormat().ordinal(), handle(depth), ARGB.redFloat(c), ARGB.greenFloat(c), ARGB.blueFloat(c), ARGB.alphaFloat(c),
                (float) depthValue, x, y, w, h);
    }

    @Override
    public void clearDepthTexture(GpuTexture depth, double depthValue) {
        if (Mtl.GPU_PROFILING) Mtl.setPassLabel("Clear depth " + describe(depth));
        assertNoRenderPass();
        Mtl.beginPass(0, false, 0, 0, 0, 0, handle(depth), true, depthValue);
        Mtl.endPass();
    }

    @Override
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        assertNoRenderPass();
        int length = data.remaining();
        if (length > slice.length()) throw new IllegalArgumentException("Cannot write more data than the slice allows (attempting to write " + length + " bytes into a slice of length " + slice.length() + ")");
        MetalBuffer buffer = (MetalBuffer) slice.buffer();
        // Orphaning keeps writes out of blit encoders, which would split the surrounding render passes.
        if (buffer.tryOrphanWrite(slice.offset(), MemoryUtil.memAddress(data), length)) return;
        UploadRing.Slice staging = UploadRing.reserve(length);
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), staging.address(), length);
        Mtl.copyBuffer(staging.buffer(), staging.offset(), buffer.handle, slice.offset(), length);
        buffer.markGpuWrite();
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBuffer buffer, boolean read, boolean write) {
        return mapBuffer(buffer.slice(), read, write);
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBufferSlice slice, boolean read, boolean write) {
        assertNoRenderPass();
        MetalBuffer buffer = (MetalBuffer) slice.buffer();
        if (buffer.isClosed()) throw new IllegalStateException("Buffer already closed");
        if (!read && !write) throw new IllegalArgumentException("At least read or write must be true");
        // Unsynchronized like Blaze3D's GL mapping; callers fence their own ring buffers.
        ByteBuffer view = buffer.view(slice.offset(), slice.length());
        return new GpuBuffer.MappedView() {
            @Override public ByteBuffer data() { return view; }
            @Override public void close() {}
        };
    }

    @Override
    public void copyToBuffer(GpuBufferSlice src, GpuBufferSlice dst) {
        assertNoRenderPass();
        if (src.length() != dst.length()) throw new IllegalArgumentException("Cannot copy from slice of size " + src.length() + " to slice of size " + dst.length() + ", they must be equal");
        Mtl.copyBuffer(((MetalBuffer) src.buffer()).handle, src.offset(), ((MetalBuffer) dst.buffer()).handle, dst.offset(), src.length());
        ((MetalBuffer) dst.buffer()).markGpuWrite();
    }

    @Override
    public void writeToTexture(GpuTexture texture, NativeImage image) {
        if (image.getWidth() != texture.getWidth(0) || image.getHeight() != texture.getHeight(0)) {
            throw new IllegalArgumentException("Cannot replace texture of size " + texture.getWidth(0) + "x" + texture.getHeight(0) + " with image of size " + image.getWidth() + "x" + image.getHeight());
        }
        writeToTexture(texture, image, 0, 0, 0, 0, image.getWidth(), image.getHeight(), 0, 0);
    }

    @Override
    public void writeToTexture(GpuTexture texture, NativeImage image, int mip, int layer, int x, int y, int w, int h, int srcX, int srcY) {
        assertNoRenderPass();
        checkTextureWrite(texture, mip, layer, x, y, w, h);
        int comps = image.format().components();
        long src = image.getPointer() + ((long) srcY * image.getWidth() + srcX) * comps;
        upload(texture, src, (long) image.getWidth() * comps, comps, mip, layer, x, y, w, h);
    }

    @Override
    public void writeToTexture(GpuTexture texture, ByteBuffer data, NativeImage.Format format, int mip, int layer, int x, int y, int w, int h) {
        assertNoRenderPass();
        checkTextureWrite(texture, mip, layer, x, y, w, h);
        if ((long) w * h * format.components() > data.remaining()) {
            throw new IllegalArgumentException("Copy would overrun the source buffer (remaining length of " + data.remaining() + ", but copy is " + w + "x" + h + " of format " + format + ")");
        }
        upload(texture, MemoryUtil.memAddress(data), (long) w * format.components(), format.components(), mip, layer, x, y, w, h);
    }

    private static void checkTextureWrite(GpuTexture texture, int mip, int layer, int x, int y, int w, int h) {
        if (mip < 0 || mip >= texture.getMipLevels()) throw new IllegalArgumentException("Invalid mipLevel " + mip + ", must be >= 0 and < " + texture.getMipLevels());
        if (x + w > texture.getWidth(mip) || y + h > texture.getHeight(mip)) {
            throw new IllegalArgumentException("Dest texture (" + texture.getWidth(mip) + "x" + texture.getHeight(mip) + ") is not large enough to write a rectangle of " + w + "x" + h + " at " + x + "x" + y);
        }
        if (texture.isClosed()) throw new IllegalStateException("Destination texture is closed");
        if ((texture.usage() & GpuTexture.USAGE_COPY_DST) == 0) throw new IllegalStateException("Color texture must have USAGE_COPY_DST to be a destination for a write");
        if (layer >= texture.getDepthOrLayers()) throw new UnsupportedOperationException("Depth or layer is out of range, must be >= 0 and < " + texture.getDepthOrLayers());
    }

    /** Packs the rectangle into a staging buffer in the texture's pixel format (expanding/narrowing channels like GL does) and blits it. */
    private static void upload(GpuTexture texture, long src, long srcStride, int srcComps, int mip, int layer, int x, int y, int w, int h) {
        if (mip >= ((MetalTexture) texture).metalMips || w == 0 || h == 0) return;
        int dstComps = texture.getFormat().pixelSize();
        int rowBytes = w * dstComps;
        UploadRing.Slice staging = UploadRing.reserve((long) rowBytes * h);
        long dst = staging.address();
        for (int row = 0; row < h; row++) {
            long s = src + row * srcStride, d = dst + (long) row * rowBytes;
            if (srcComps == dstComps) {
                MemoryUtil.memCopy(s, d, rowBytes);
            } else {
                for (int px = 0; px < w; px++) {
                    for (int c = 0; c < dstComps; c++) {
                        byte v = c < srcComps ? MemoryUtil.memGetByte(s + (long) px * srcComps + c) : (byte) (c == 3 ? 0xFF : 0);
                        MemoryUtil.memPutByte(d + (long) px * dstComps + c, v);
                    }
                }
            }
        }
        int slice = (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? layer % 6 : 0;
        Mtl.copyBufferToTexture(staging.buffer(), staging.offset(), rowBytes, handle(texture), slice, mip, x, y, w, h);
    }

    @Override
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable callback, int mip) {
        copyTextureToBuffer(texture, buffer, offset, callback, mip, 0, 0, texture.getWidth(mip), texture.getHeight(mip));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable callback, int mip, int x, int y, int w, int h) {
        assertNoRenderPass();
        if (mip < 0 || mip >= texture.getMipLevels()) throw new IllegalArgumentException("Invalid mipLevel " + mip + ", must be >= 0 and < " + texture.getMipLevels());
        if ((long) w * h * texture.getFormat().pixelSize() + offset > buffer.size()) {
            throw new IllegalArgumentException("Buffer of size " + buffer.size() + " is not large enough to hold " + w + "x" + h + " pixels (" + texture.getFormat().pixelSize() + " bytes each) starting from offset " + offset);
        }
        if (texture.isClosed()) throw new IllegalStateException("Source texture is closed");
        if (buffer.isClosed()) throw new IllegalStateException("Destination buffer is closed");
        ((MetalBuffer) buffer).markGpuWrite();
        if (mip < ((MetalTexture) texture).metalMips) Mtl.copyTextureToBuffer(handle(texture), mip, x, y, w, h, ((MetalBuffer) buffer).handle, offset, w * texture.getFormat().pixelSize());
        RenderSystem.queueFencedTask(callback);
    }

    @Override
    public void copyTextureToTexture(GpuTexture src, GpuTexture dst, int mip, int dstX, int dstY, int srcX, int srcY, int w, int h) {
        assertNoRenderPass();
        if (src.isClosed()) throw new IllegalStateException("Source texture is closed");
        if (dst.isClosed()) throw new IllegalStateException("Destination texture is closed");
        if (mip < Math.min(((MetalTexture) src).metalMips, ((MetalTexture) dst).metalMips)) Mtl.copyTextureToTexture(handle(src), handle(dst), mip, dstX, dstY, srcX, srcY, w, h);
    }

    @Override
    public void presentTexture(GpuTextureView view) {
        assertNoRenderPass();
        if (!view.texture().getFormat().hasColorAspect()) throw new IllegalStateException("Cannot present a non-color texture!");
        Mtl.present(handle(view.texture()));
    }

    @Override
    public GpuFence createFence() {
        assertNoRenderPass();
        return new MetalFence(Mtl.fence());
    }

    private @Nullable MetalQuery activeQuery;

    @Override
    public GpuQuery timerQueryBegin() {
        RenderSystem.assertOnRenderThread();
        if (activeQuery != null) throw new IllegalStateException("A timer query is already active");
        activeQuery = new MetalQuery();
        return activeQuery;
    }

    @Override
    public void timerQueryEnd(GpuQuery query) {
        RenderSystem.assertOnRenderThread();
        if (query != activeQuery) throw new IllegalStateException("Mismatched or duplicate GpuQuery when ending timerQuery");
        activeQuery.end();
        activeQuery = null;
    }

    private static String describe(GpuTexture texture) {
        return texture.getLabel() + " " + texture.getWidth(0) + "x" + texture.getHeight(0);
    }

    private static long handle(GpuTexture texture) {
        return ((MetalTexture) texture).handle;
    }
}
