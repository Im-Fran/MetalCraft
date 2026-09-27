package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import cl.franciscosolis.metalcraft.shader.ShaderTranslator;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** GL-style render pass state (uniforms/samplers bound by name, persisting across pipeline changes) replayed onto a Metal render encoder. */
public class MetalRenderPass implements RenderPass {
    private final MetalCommandEncoder encoder;
    private final int colorFormat, depthFormat, width, height;
    private @Nullable MetalPipeline pipeline, boundPipeline;
    private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final Map<String, Binding> samplers = new HashMap<>();
    private @Nullable GpuBuffer vertexBuffer, boundVertexBuffer;
    private @Nullable GpuBuffer indexBuffer;
    private VertexFormat.IndexType indexType = VertexFormat.IndexType.INT;
    private boolean scissor;
    private int scissorX, scissorY, scissorW, scissorH;
    private long boundScissor = -1;
    private int debugGroups;
    private boolean closed;

    private record Binding(MetalTextureView view, MetalSampler sampler) {}

    MetalRenderPass(MetalCommandEncoder encoder, GpuTextureView color, @Nullable GpuTextureView depth) {
        this.encoder = encoder;
        this.colorFormat = color.texture().getFormat().ordinal();
        this.depthFormat = depth != null ? depth.texture().getFormat().ordinal() : -1;
        this.width = color.getWidth(0);
        this.height = color.getHeight(0);
    }

    // ponytail: debug groups are counted but not forwarded; push them to the encoder when GPU captures need labels.
    @Override
    public void pushDebugGroup(Supplier<String> label) {
        debugGroups++;
    }

    @Override
    public void popDebugGroup() {
        if (debugGroups == 0) throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        debugGroups--;
    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
        this.pipeline = encoder.device.getOrCompilePipeline(pipeline);
    }

    @Override
    public void bindTexture(String name, @Nullable GpuTextureView view, @Nullable GpuSampler sampler) {
        if (sampler == null || view == null) samplers.remove(name);
        else samplers.put(name, new Binding((MetalTextureView) view, (MetalSampler) sampler));
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        uniforms.put(name, buffer.slice());
    }

    @Override
    public void setUniform(String name, GpuBufferSlice slice) {
        if (slice.offset() % encoder.device.getUniformOffsetAlignment() > 0) {
            throw new IllegalArgumentException("Uniform buffer offset must be aligned to " + encoder.device.getUniformOffsetAlignment());
        }
        uniforms.put(name, slice);
    }

    @Override
    public void enableScissor(int x, int y, int w, int h) {
        scissor = true;
        scissorX = x;
        scissorY = y;
        scissorW = w;
        scissorH = h;
    }

    @Override
    public void disableScissor() {
        scissor = false;
    }

    @Override
    public void setVertexBuffer(int slot, GpuBuffer buffer) {
        if (slot != 0) throw new IllegalArgumentException("Vertex buffer slot is out of range: " + slot);
        vertexBuffer = buffer;
    }

    @Override
    public void setIndexBuffer(@Nullable GpuBuffer buffer, VertexFormat.IndexType type) {
        indexBuffer = buffer;
        indexType = type;
    }

    @Override
    public void drawIndexed(int baseVertex, int firstIndex, int count, int instances) {
        checkOpen();
        if (setup()) drawIndexed(indexBuffer, indexType, baseVertex, firstIndex, count, instances);
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer, VertexFormat.@Nullable IndexType defaultIndexType,
                                        Collection<String> dynamicUniforms, T context) {
        checkOpen();
        VertexFormat.IndexType fallbackType = defaultIndexType != null ? defaultIndexType : VertexFormat.IndexType.SHORT;
        for (Draw<T> draw : draws) {
            vertexBuffer = draw.vertexBuffer();
            if (draw.uniformUploaderConsumer() != null) draw.uniformUploaderConsumer().accept(context, uniforms::put);
            if (!setup()) return;
            GpuBuffer ib = draw.indexBuffer() != null ? draw.indexBuffer() : defaultIndexBuffer;
            drawIndexed(ib, draw.indexType() != null ? draw.indexType() : fallbackType, 0, draw.firstIndex(), draw.indexCount(), 1);
        }
    }

    @Override
    public void draw(int first, int count) {
        checkOpen();
        if (!setup()) return;
        if (pipeline.info.getVertexFormatMode() == VertexFormat.Mode.TRIANGLE_FAN) {
            if (count >= 3) Mtl.drawIndexed(Mtl.PRIMITIVE_TRIANGLE, (count - 2) * 3, true, sequentialFanIndices(count), 0, 1, first);
        } else {
            Mtl.draw(pipeline.primitive, first, count, 1);
        }
    }

    private void drawIndexed(@Nullable GpuBuffer ib, VertexFormat.IndexType type, int baseVertex, int firstIndex, int count, int instances) {
        if (ib == null) throw new IllegalStateException("Missing index buffer");
        if (pipeline.info.getVertexFormatMode() == VertexFormat.Mode.TRIANGLE_FAN) {
            long base = ((MetalBuffer) ib).address() + (long) firstIndex * type.bytes;
            drawFan(type == VertexFormat.IndexType.SHORT ? i -> Short.toUnsignedInt(MemoryUtil.memGetShort(base + 2L * i)) : i -> MemoryUtil.memGetInt(base + 4L * i),
                    baseVertex, count, instances);
            return;
        }
        Mtl.drawIndexed(pipeline.primitive, count, type == VertexFormat.IndexType.INT, ((MetalBuffer) ib).handle, (long) firstIndex * type.bytes, instances, baseVertex);
    }

    private static long fanIndices;
    private static int fanCapacity;

    /**
     * Triangle-list indices for a fan of {@code count} consecutive vertices. The list for n vertices is a prefix of the
     * list for any larger n, so one shared buffer, grown on demand, serves every size (the sky draws fans each frame).
     */
    private static long sequentialFanIndices(int count) {
        if (count > fanCapacity) {
            int capacity = Math.max(count, 64);
            long buffer = Mtl.newBuffer((capacity - 2) * 12L);
            long p = Mtl.bufferContents(buffer);
            for (int i = 0; i < capacity - 2; i++) {
                MemoryUtil.memPutInt(p + i * 12L, 0);
                MemoryUtil.memPutInt(p + i * 12L + 4, i + 1);
                MemoryUtil.memPutInt(p + i * 12L + 8, i + 2);
            }
            Mtl.release(fanIndices);  // Draws already recorded keep the old buffer alive.
            fanIndices = buffer;
            fanCapacity = capacity;
        }
        return fanIndices;
    }

    /** Metal has no triangle fans: expand (v0, vi, vi+1) into a triangle list. */
    // ponytail: indexed fans only come from debug renderers, so they're expanded on the CPU into a throwaway buffer.
    private void drawFan(java.util.function.IntUnaryOperator index, int baseVertex, int count, int instances) {
        if (count < 3) return;
        int triangles = count - 2;
        long buffer = Mtl.newBuffer(triangles * 12L);
        long p = Mtl.bufferContents(buffer);
        for (int i = 0; i < triangles; i++) {
            MemoryUtil.memPutInt(p + i * 12L, index.applyAsInt(0));
            MemoryUtil.memPutInt(p + i * 12L + 4, index.applyAsInt(i + 1));
            MemoryUtil.memPutInt(p + i * 12L + 8, index.applyAsInt(i + 2));
        }
        Mtl.drawIndexed(Mtl.PRIMITIVE_TRIANGLE, triangles * 3, true, buffer, 0, instances, baseVertex);
        Mtl.release(buffer);
    }

    /** Binds pipeline state and every resource the pipeline's shaders reference. Returns false (skip the draw) for invalid pipelines, like GL does. */
    private boolean setup() {
        if (pipeline == null || !pipeline.isValid()) return false;
        if (pipeline != boundPipeline) {
            long pso = pipeline.state(colorFormat, depthFormat);
            if (pso == 0) return false;
            RenderPipeline info = pipeline.info;
            Mtl.setPipelineState(pso, depthFormat >= 0 ? pipeline.depthState : encoder.noDepthState(), pipeline.cull,
                    info.getPolygonMode() == PolygonMode.WIREFRAME, info.getDepthBiasConstant(), info.getDepthBiasScaleFactor());
            boundPipeline = pipeline;
        }

        if (vertexBuffer != null && vertexBuffer != boundVertexBuffer && !vertexBuffer.isClosed()) {
            Mtl.setBuffer(false, ShaderTranslator.VERTEX_BUFFER_INDEX, ((MetalBuffer) vertexBuffer).handle, 0);
            boundVertexBuffer = vertexBuffer;
        }

        bindStage(pipeline.vertex, false);
        bindStage(pipeline.fragment, true);

        int x = 0, y = 0, w = width, h = height;
        if (scissor) {
            // Metal rejects scissor rects outside the attachment; GL just clips.
            x = Math.clamp(scissorX, 0, width);
            y = Math.clamp(scissorY, 0, height);
            w = Math.clamp(scissorX + scissorW, x, width) - x;
            h = Math.clamp(scissorY + scissorH, y, height) - y;
        }
        long key = ((long) x << 48) | ((long) y << 32) | ((long) w << 16) | h;
        if (key != boundScissor) {
            Mtl.setScissor(x, y, w, h);
            boundScissor = key;
        }
        return true;
    }

    private void bindStage(ShaderTranslator.Result stage, boolean fragment) {
        for (var e : stage.buffers().entrySet()) {
            GpuBufferSlice slice = uniforms.get(e.getKey());
            // Stale bindings to buffers Minecraft has since closed are harmless in GL but dangling pointers in Metal.
            if (slice != null && !slice.buffer().isClosed()) bindBuffer(fragment, e.getValue(), ((MetalBuffer) slice.buffer()).handle, slice.offset());
        }
        for (var e : stage.textures().entrySet()) {
            String name = e.getKey();
            Binding binding = samplers.get(name);
            if (binding != null && !binding.view.isClosed() && !binding.sampler.isClosed()) {
                bindTexture(fragment, e.getValue(), binding.view.handle, stage.samplers().getOrDefault(name, -1), binding.sampler.handle);
                continue;
            }
            GpuBufferSlice texel = uniforms.get(name);
            TextureFormat format = texel != null ? pipeline.texelFormat(name) : null;
            if (format != null && !texel.buffer().isClosed()) bindTexture(fragment, e.getValue(), ((MetalBuffer) texel.buffer()).texelView(format), -1, 0);
        }
    }

    // What this pass has bound per [stage][index], so unchanged resources skip the JNI call. A merged encoder may
    // still hold older bindings, which is fine: starting empty only means rebinding once.
    private final long[][] boundBuffers = new long[2][32], boundOffsets = new long[2][32];
    private final long[][] boundTextures = new long[2][32], boundSamplers = new long[2][32];

    private void bindBuffer(boolean fragment, int index, long buffer, long offset) {
        int s = fragment ? 1 : 0;
        if (boundBuffers[s][index] == buffer && boundOffsets[s][index] == offset) return;
        boundBuffers[s][index] = buffer;
        boundOffsets[s][index] = offset;
        Mtl.setBuffer(fragment, index, buffer, offset);
    }

    private void bindTexture(boolean fragment, int index, long texture, int samplerIndex, long sampler) {
        int s = fragment ? 1 : 0;
        if (boundTextures[s][index] == texture && (samplerIndex < 0 || boundSamplers[s][samplerIndex] == sampler)) return;
        boundTextures[s][index] = texture;
        if (samplerIndex >= 0) boundSamplers[s][samplerIndex] = sampler;
        Mtl.setTexture(fragment, index, texture, samplerIndex, sampler);
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Can't use a closed render pass");
    }

    @Override
    public void close() {
        if (closed) return;
        if (debugGroups > 0) throw new IllegalStateException("Render pass had debug groups left open!");
        closed = true;
        encoder.finishRenderPass();
    }
}
