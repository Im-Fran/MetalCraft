package cl.franciscosolis.metalcraft.compat.sodium;

import cl.franciscosolis.metalcraft.MetalCraft;
import cl.franciscosolis.metalcraft.device.MetalPipeline;
import cl.franciscosolis.metalcraft.device.MetalSampler;
import cl.franciscosolis.metalcraft.device.MetalTextureView;
import cl.franciscosolis.metalcraft.device.UploadRing;
import cl.franciscosolis.metalcraft.mtl.Mtl;
import cl.franciscosolis.metalcraft.shader.ShaderTranslator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferTarget;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlIndexType;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import net.caffeinemc.mods.sodium.client.gl.tessellation.TessellationBinding;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

/**
 * Runs Sodium's OpenGL object model on Metal. Sodium keeps its GL-shaped code; mixins route each GL call here.
 * Handles stay plain ints, as Sodium expects. Render thread only.
 */
public final class SodiumMetal {
    private static final int GL_VERTEX_SHADER = 0x8B31;
    private static int nextHandle = 1;

    private SodiumMetal() {}

    /** Backs the GL calls Sodium makes from its own mixin handlers (fences, context queries) with native stand-ins. */
    public static void installGlStubs() {
        GL.destroy();
        GL.create(name -> Mtl.glStubAddress(MemoryUtil.memASCII(MemoryUtil.memAddress(name))));  // name is NUL-terminated
        GL.createCapabilities();
    }

    public static int newHandle() {
        return nextHandle++;
    }

    // ---- Buffers ---------------------------------------------------------------------------------

    private static final class Buffer {
        long mtl, contents, size;
    }

    private static final Int2ObjectOpenHashMap<Buffer> BUFFERS = new Int2ObjectOpenHashMap<>();

    public static int genBuffer() {
        int handle = newHandle();
        BUFFERS.put(handle, new Buffer());
        return handle;
    }

    public static void deleteBuffer(int handle) {
        Buffer b = BUFFERS.remove(handle);
        if (b != null) Mtl.release(b.mtl);
    }

    private static Buffer buffer(int handle) {
        Buffer b = BUFFERS.get(handle);
        if (b == null) throw new IllegalStateException("Unknown buffer handle " + handle);
        return b;
    }

    /** glBufferData: new storage, like GL orphaning. GPU work already queued keeps the old MTLBuffer alive. */
    public static void allocate(int handle, long size, long data) {
        Buffer b = buffer(handle);
        Mtl.release(b.mtl);
        b.mtl = Mtl.newBuffer(Math.max(16, (size + 15) & ~15L));
        b.contents = Mtl.bufferContents(b.mtl);
        b.size = size;
        if (data != 0) MemoryUtil.memCopy(data, b.contents, size);
    }

    /** glBufferSubData, ordered with already-recorded GPU work. */
    public static void subData(int handle, long offset, long data, long length) {
        Buffer b = buffer(handle);
        if (offset == 0 && length >= b.size) {
            // Whole-buffer rewrite (Sodium's per-frame chunk fade data, sent mid-pass): orphan instead of blitting.
            allocate(handle, b.size, data);
            return;
        }
        requireNoPass("glBufferSubData");
        UploadRing.Slice staging = UploadRing.reserve(length);
        MemoryUtil.memCopy(data, staging.address(), length);
        Mtl.copyBuffer(staging.buffer(), staging.offset(), b.mtl, offset, length);
    }

    public static void copy(int src, int dst, long readOffset, long writeOffset, long length) {
        requireNoPass("glCopyBufferSubData");
        Mtl.copyBuffer(buffer(src).mtl, readOffset, buffer(dst).mtl, writeOffset, length);
    }

    /** Shared storage: the mapping is the buffer itself, so flushes and unmaps are no-ops. */
    public static ByteBuffer map(int handle, long offset, long length) {
        return MemoryUtil.memByteBuffer(buffer(handle).contents + offset, (int) length);
    }

    // ---- Shaders & programs ----------------------------------------------------------------------

    private static final Int2ObjectOpenHashMap<String> SHADER_SOURCES = new Int2ObjectOpenHashMap<>();
    private static final Int2IntOpenHashMap SHADER_TYPES = new Int2IntOpenHashMap();

    public static int createShader(int type) {
        int handle = newHandle();
        SHADER_TYPES.put(handle, type);
        return handle;
    }

    public static void shaderSource(int shader, CharSequence source) {
        SHADER_SOURCES.put(shader, source.toString());
    }

    public static void deleteShader(int shader) {
        SHADER_SOURCES.remove(shader);
        SHADER_TYPES.remove(shader);
    }

    private record PsoKey(RenderPipeline pipeline, int colorFormat, int depthFormat, GlVertexAttributeBinding[] attributes) {}

    private static final class Program {
        final IntArrayList shaders = new IntArrayList();
        final Map<String, Integer> attributes = new HashMap<>();
        final List<String> uniformNames = new ArrayList<>();  // GL uniform location -> name
        final List<String> blockNames = new ArrayList<>();    // GL block index -> name
        final Object2IntOpenHashMap<String> blockBindings = new Object2IntOpenHashMap<>();
        final Object2IntOpenHashMap<String> samplerUnits = new Object2IntOpenHashMap<>();
        final Map<PsoKey, Long> pipelines = new HashMap<>();
        ShaderTranslator.Result vs, fs;
        long vsFn, fsFn, vsDefaults, fsDefaults;
        String log = "";
    }

    private static final Int2ObjectOpenHashMap<Program> PROGRAMS = new Int2ObjectOpenHashMap<>();
    private static @Nullable Program current;

    public static int createProgram() {
        int handle = newHandle();
        PROGRAMS.put(handle, new Program());
        return handle;
    }

    public static void attachShader(int program, int shader) {
        PROGRAMS.get(program).shaders.add(shader);
    }

    public static void bindAttribLocation(int program, int index, CharSequence name) {
        PROGRAMS.get(program).attributes.put(name.toString(), index);
    }

    public static void link(int handle) {
        Program p = PROGRAMS.get(handle);
        String vsh = null, fsh = null;
        for (int shader : p.shaders) {
            if (SHADER_TYPES.get(shader) == GL_VERTEX_SHADER) vsh = SHADER_SOURCES.get(shader);
            else fsh = SHADER_SOURCES.get(shader);
        }
        try {
            // Attribute locations follow glBindAttribLocation, so Sodium's attribute indices are also Metal's.
            p.vs = ShaderTranslator.translate("sodium.vsh", vsh, ShaderTranslator.Stage.VERTEX, p.attributes);
            p.fs = ShaderTranslator.translate("sodium.fsh", fsh, ShaderTranslator.Stage.FRAGMENT, p.vs.outputs());
            p.vsFn = function(p.vs);
            p.fsFn = function(p.fs);
            p.vsDefaults = MemoryUtil.nmemCalloc(1, Math.max(16, p.vs.defaultsSize()));
            p.fsDefaults = MemoryUtil.nmemCalloc(1, Math.max(16, p.fs.defaultsSize()));
        } catch (ShaderTranslator.TranslationException | IllegalStateException e) {
            p.log = String.valueOf(e.getMessage());
            MetalCraft.LOGGER.error("Couldn't translate Sodium shader program: {}", p.log);
        }
    }

    private static long function(ShaderTranslator.Result r) {
        long library = Mtl.newLibrary(r.msl());
        long fn = Mtl.newFunction(library, r.entryPoint());
        Mtl.release(library);
        return fn;
    }

    public static int linkStatus(int program) {
        return PROGRAMS.get(program).vsFn != 0 ? 1 : 0;
    }

    public static String programLog(int program) {
        return PROGRAMS.get(program).log;
    }

    public static void useProgram(int program) {
        current = program == 0 ? null : PROGRAMS.get(program);
    }

    public static void deleteProgram(int handle) {
        Program p = PROGRAMS.remove(handle);
        if (p == null) return;
        if (current == p) current = null;
        p.pipelines.values().forEach(Mtl::release);
        Mtl.release(p.vsFn);
        Mtl.release(p.fsFn);
        MemoryUtil.nmemFree(p.vsDefaults);
        MemoryUtil.nmemFree(p.fsDefaults);
    }

    /** Locations index a per-program name table; -1 for names neither stage uses (GL semantics). */
    public static int uniformLocation(int program, CharSequence nameSeq) {
        Program p = PROGRAMS.get(program);
        String name = nameSeq.toString();
        if (p.vs == null || !(uses(p.vs, name) || uses(p.fs, name))) return -1;
        int location = p.uniformNames.indexOf(name);
        if (location < 0) {
            p.uniformNames.add(name);
            location = p.uniformNames.size() - 1;
        }
        return location;
    }

    private static boolean uses(ShaderTranslator.Result stage, String name) {
        return stage.defaults().containsKey(name) || stage.textures().containsKey(name);
    }

    public static int uniformBlockIndex(int program, CharSequence nameSeq) {
        Program p = PROGRAMS.get(program);
        String name = nameSeq.toString();
        if (p.vs == null || !(p.vs.buffers().containsKey(name) || p.fs.buffers().containsKey(name))) return -1;
        if (!p.blockNames.contains(name)) p.blockNames.add(name);
        return p.blockNames.indexOf(name);
    }

    public static void uniformBlockBinding(int program, int blockIndex, int binding) {
        Program p = PROGRAMS.get(program);
        p.blockBindings.put(p.blockNames.get(blockIndex), binding);
    }

    // ---- Uniform values (apply to the current program, like glUniform*) ------------------------

    public static void uniformInt(int location, int value) {
        Program p = current;
        if (p == null || location < 0) return;
        String name = p.uniformNames.get(location);
        if (p.vs.textures().containsKey(name) || p.fs.textures().containsKey(name)) {
            p.samplerUnits.put(name, value);  // Sampler uniforms hold texture units.
            return;
        }
        write(p, name, addr -> MemoryUtil.memPutInt(addr, value));
    }

    public static void uniformFloats(int location, float... values) {
        Program p = current;
        if (p == null || location < 0) return;
        write(p, p.uniformNames.get(location), addr -> {
            for (int i = 0; i < values.length; i++) MemoryUtil.memPutFloat(addr + 4L * i, values[i]);
        });
    }

    public static void uniformMatrix4(int location, FloatBuffer matrix) {
        Program p = current;
        if (p == null || location < 0) return;
        write(p, p.uniformNames.get(location), addr -> MemoryUtil.memCopy(MemoryUtil.memAddress(matrix), addr, 64));
    }

    private static void write(Program p, String name, java.util.function.LongConsumer writer) {
        Integer vsOffset = p.vs.defaults().get(name);
        if (vsOffset != null) writer.accept(p.vsDefaults + vsOffset);
        Integer fsOffset = p.fs.defaults().get(name);
        if (fsOffset != null) writer.accept(p.fsDefaults + fsOffset);
    }

    // ---- Global binding points ---------------------------------------------------------------

    private static final int[] UNIFORM_BUFFERS = new int[16];
    private static final long[] UNIT_VIEWS = new long[16];
    private static final long[] UNIT_SAMPLERS = new long[16];

    public static void bindUniformBuffer(int binding, int buffer) {
        UNIFORM_BUFFERS[binding] = buffer;
    }

    public static void bindTexture(int unit, GpuTextureView view, GpuSampler sampler) {
        UNIT_VIEWS[unit] = ((MetalTextureView) view).handle();
        UNIT_SAMPLERS[unit] = ((MetalSampler) sampler).handle();
    }

    // ---- Render pass & draws -------------------------------------------------------------------

    private static @Nullable RenderPass pass;
    private static @Nullable RenderPipeline pipeline;
    private static int colorFormat, depthFormat;
    private static long boundPso;
    private static final Map<RenderPipeline, Long> DEPTH_STATES = new HashMap<>();
    private static long noDepthState;

    /** Replaces Sodium binding the target's GL framebuffer and applying the pipeline's GL state. */
    public static void beginPass(RenderTarget target, RenderPipeline renderPipeline) {
        GpuTextureView color = target.getColorTextureView();
        GpuTextureView depth = target.getDepthTextureView();
        pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Sodium terrain " + renderPipeline.getLocation().getPath(), color, OptionalInt.empty(), depth, OptionalDouble.empty());
        pipeline = renderPipeline;
        colorFormat = color.texture().getFormat().ordinal();
        depthFormat = depth != null ? depth.texture().getFormat().ordinal() : -1;
        boundPso = 0;
    }

    public static void endPass() {
        if (pass != null) pass.close();
        pass = null;
        pipeline = null;
    }

    private static void requireNoPass(String what) {
        if (pass != null) throw new IllegalStateException(what + " inside Sodium's terrain pass can't be ordered on Metal");
    }

    public static void multiDraw(GlPrimitiveType primitiveType, TessellationBinding[] bindings, MultiDrawBatch batch, GlIndexType indexType) {
        Program p = current;
        if (p == null || p.vsFn == 0 || pass == null || batch.size == 0) return;
        if (indexType == GlIndexType.UNSIGNED_BYTE) throw new UnsupportedOperationException("Metal has no 8-bit index buffers");

        Buffer vertices = null, indices = null;
        GlVertexAttributeBinding[] attributes = null;
        for (TessellationBinding binding : bindings) {
            if (binding.target() == GlBufferTarget.ELEMENT_BUFFER) {
                indices = buffer(binding.buffer().handle());
            } else {
                vertices = buffer(binding.buffer().handle());
                attributes = binding.attributeBindings();
            }
        }
        if (vertices == null || indices == null) return;

        long pso = p.pipelines.computeIfAbsent(new PsoKey(pipeline, colorFormat, depthFormat, attributes), SodiumMetal::buildPipeline);
        if (pso == 0) return;
        if (pso != boundPso) {
            Mtl.setPipelineState(pso, depthState(), pipeline.isCull() ? Mtl.CULL_BACK : Mtl.CULL_NONE,
                    pipeline.getPolygonMode() == PolygonMode.WIREFRAME, pipeline.getDepthBiasConstant(), pipeline.getDepthBiasScaleFactor());
            boundPso = pso;
        }
        Mtl.setBuffer(false, ShaderTranslator.VERTEX_BUFFER_INDEX, vertices.mtl, 0);
        bindStage(p, p.vs, p.vsDefaults, false);
        bindStage(p, p.fs, p.fsDefaults, true);
        Mtl.multiDrawIndexed(primitive(primitiveType), indexType == GlIndexType.UNSIGNED_INT, indices.mtl,
                batch.pElementCount, batch.pElementPointer, batch.pBaseVertex, batch.size);
    }

    private static void bindStage(Program p, ShaderTranslator.Result stage, long defaults, boolean fragment) {
        for (var e : stage.buffers().entrySet()) {
            if (e.getKey().equals(ShaderTranslator.DEFAULT_BLOCK)) {
                Mtl.setBytes(fragment, e.getValue(), defaults, stage.defaultsSize());
            } else if (p.blockBindings.containsKey(e.getKey())) {
                Buffer b = BUFFERS.get(UNIFORM_BUFFERS[p.blockBindings.getInt(e.getKey())]);
                if (b != null) Mtl.setBuffer(fragment, e.getValue(), b.mtl, 0);
            }
        }
        for (var e : stage.textures().entrySet()) {
            if (!p.samplerUnits.containsKey(e.getKey())) continue;
            int unit = p.samplerUnits.getInt(e.getKey());
            Mtl.setTexture(fragment, e.getValue(), UNIT_VIEWS[unit], stage.samplers().getOrDefault(e.getKey(), -1), UNIT_SAMPLERS[unit]);
        }
    }

    private static long depthState() {
        if (depthFormat < 0) {
            if (noDepthState == 0) noDepthState = Mtl.newDepthStencilState(Mtl.COMPARE_ALWAYS, false);
            return noDepthState;
        }
        return DEPTH_STATES.computeIfAbsent(pipeline, rp -> Mtl.newDepthStencilState(MetalPipeline.compare(rp.getDepthTestFunction()),
                rp.getDepthTestFunction() != DepthTestFunction.NO_DEPTH_TEST && rp.isWriteDepth()));
    }

    private static long buildPipeline(PsoKey key) {
        Program p = current;
        IntArrayList attribs = new IntArrayList();
        int stride = 0;
        for (GlVertexAttributeBinding a : key.attributes()) {
            if (!p.vs.inputs().containsValue(a.getIndex())) continue;
            attribs.add(a.getIndex());
            attribs.add(vertexFormat(a));
            attribs.add(a.getPointer());
            stride = a.getStride();
        }
        BlendFunction blend = key.pipeline().getBlendFunction().orElse(null);
        int writeMask = (key.pipeline().isWriteColor() ? 0b1110 : 0) | (key.pipeline().isWriteAlpha() ? 0b0001 : 0);
        try {
            return Mtl.newRenderPipeline(p.vsFn, p.fsFn, key.colorFormat(), key.depthFormat(), blend != null,
                    blend != null ? MetalPipeline.blendFactor(blend.sourceColor().name()) : 0, blend != null ? MetalPipeline.blendFactor(blend.destColor().name()) : 0,
                    blend != null ? MetalPipeline.blendFactor(blend.sourceAlpha().name()) : 0, blend != null ? MetalPipeline.blendFactor(blend.destAlpha().name()) : 0,
                    writeMask, attribs.toIntArray(), new int[0], stride, ShaderTranslator.VERTEX_BUFFER_INDEX, "sodium:" + key.pipeline().getLocation());
        } catch (IllegalStateException e) {
            MetalCraft.LOGGER.error("Couldn't create Metal pipeline for Sodium terrain ({}): {}", key.pipeline().getLocation(), e.getMessage());
            return 0;
        }
    }

    private static int primitive(GlPrimitiveType type) {
        return switch (type) {
            case POINTS -> Mtl.PRIMITIVE_POINT;
            case LINES -> Mtl.PRIMITIVE_LINE;
            case TRIANGLES -> Mtl.PRIMITIVE_TRIANGLE;
            case PATCHES -> throw new UnsupportedOperationException("Tessellation patches aren't supported on Metal");
        };
    }

    /** GL vertex attribute (type, count, normalized, integer) -> MTLVertexFormat. */
    private static int vertexFormat(GlVertexAttributeBinding a) {
        int n = a.getCount() - 1;
        boolean integer = a.isIntType(), normalized = a.isNormalized();
        return switch (a.getFormat()) {
            case 0x1406 -> 28 + n;  // FLOAT
            case 0x1401 -> integer ? new int[]{45, 1, 2, 3}[n] : normalized ? new int[]{47, 7, 8, 9}[n] : unsupported(a);    // UNSIGNED_BYTE
            case 0x1400 -> integer ? new int[]{46, 4, 5, 6}[n] : normalized ? new int[]{48, 10, 11, 12}[n] : unsupported(a); // BYTE
            case 0x1403 -> integer ? new int[]{49, 13, 14, 15}[n] : normalized ? new int[]{51, 19, 20, 21}[n] : unsupported(a); // UNSIGNED_SHORT
            case 0x1402 -> integer ? new int[]{50, 16, 17, 18}[n] : normalized ? new int[]{52, 22, 23, 24}[n] : unsupported(a); // SHORT
            case 0x1405 -> integer ? 36 + n : unsupported(a);  // UNSIGNED_INT
            case 0x1404 -> integer ? 32 + n : unsupported(a);  // INT
            default -> unsupported(a);
        };
    }

    /** Integer data read as unnormalized float has no Metal vertex format. */
    private static int unsupported(GlVertexAttributeBinding a) {
        throw new UnsupportedOperationException("Unsupported vertex attribute format 0x" + Integer.toHexString(a.getFormat()) + " x" + a.getCount());
    }
}
