package cl.franciscosolis.metalcraft.device;

import cl.franciscosolis.metalcraft.mtl.Mtl;
import cl.franciscosolis.metalcraft.shader.ShaderTranslator;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import net.fabricmc.loader.api.FabricLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.MemoryUtil;

public class MetalDevice implements GpuDevice {
    private static final int MAX_ANISOTROPY = 16;
    // Metal requires 256-byte aligned offsets for constant-address-space buffers on macOS.
    private static final int UNIFORM_OFFSET_ALIGNMENT = 256;

    private final ShaderSource defaultShaderSource;
    private final MetalCommandEncoder encoder = new MetalCommandEncoder(this);
    private final Map<RenderPipeline, MetalPipeline> pipelines = new HashMap<>();
    private final String deviceName;

    public MetalDevice(long window, ShaderSource defaultShaderSource) {
        Mtl.init(GLFWNativeCocoa.glfwGetCocoaWindow(window));
        if (Mtl.GPU_PROFILING) Mtl.setGpuProfiling(true);
        ShaderTranslator.enableCache(FabricLoader.getInstance().getGameDir().resolve("metalcraft/shader-cache"));
        this.defaultShaderSource = defaultShaderSource;
        this.deviceName = Mtl.deviceName();
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        return encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
        if (maxAnisotropy < 1 || maxAnisotropy > MAX_ANISOTROPY) {
            throw new IllegalArgumentException("maxAnisotropy out of range; must be >= 1 and <= " + MAX_ANISOTROPY + ", but was " + maxAnisotropy);
        }
        return new MetalSampler(u, v, min, mag, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(@Nullable Supplier<String> label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        return createTexture(label != null ? label.get() : null, usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(@Nullable String label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        if (mipLevels < 1) throw new IllegalArgumentException("mipLevels must be at least 1");
        if (depthOrLayers < 1) throw new IllegalArgumentException("depthOrLayers must be at least 1");
        boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
        if (cube) {
            if (width != height) throw new IllegalArgumentException("Cubemap compatible textures must be square, but size is " + width + "x" + height);
            if (depthOrLayers % 6 != 0) throw new IllegalArgumentException("Cubemap compatible textures must have a layer count with a multiple of 6, was " + depthOrLayers);
            if (depthOrLayers > 6) throw new UnsupportedOperationException("Array textures are not yet supported");
        } else if (depthOrLayers > 1) {
            throw new UnsupportedOperationException("Array or 3D textures are not yet supported");
        }
        return new MetalTexture(usage, label != null ? label : "texture", format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        return createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mipLevels) {
        if (texture.isClosed()) throw new IllegalArgumentException("Can't create texture view with closed texture");
        if (baseMip < 0 || baseMip + mipLevels > texture.getMipLevels()) {
            throw new IllegalArgumentException(mipLevels + " mip levels starting from " + baseMip + " would be out of range for texture with only " + texture.getMipLevels() + " mip levels");
        }
        return new MetalTextureView((MetalTexture) texture, baseMip, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
        if (size <= 0) throw new IllegalArgumentException("Buffer size must be greater than zero");
        return new MetalBuffer(usage, size);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
        if (!data.hasRemaining()) throw new IllegalArgumentException("Buffer source must not be empty");
        MetalBuffer buffer = new MetalBuffer(usage, data.remaining());
        // A fresh buffer isn't referenced by any queued GPU work yet, so a direct CPU write is correctly ordered.
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.address(), data.remaining());
        return buffer;
    }

    @Override
    public String getImplementationInformation() {
        return "Metal on " + deviceName;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public String getVendor() {
        return "Apple";
    }

    @Override
    public String getBackendName() {
        return "Metal";
    }

    @Override
    public String getVersion() {
        return "MSL 2.4";
    }

    @Override
    public String getRenderer() {
        return deviceName;
    }

    @Override
    public int getMaxTextureSize() {
        return Mtl.maxTextureSize();
    }

    @Override
    public int getUniformOffsetAlignment() {
        return UNIFORM_OFFSET_ALIGNMENT;
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, @Nullable ShaderSource source) {
        ShaderSource src = source != null ? source : defaultShaderSource;
        return pipelines.computeIfAbsent(pipeline, p -> MetalPipeline.compile(p, src));
    }

    MetalPipeline getOrCompilePipeline(RenderPipeline pipeline) {
        return pipelines.computeIfAbsent(pipeline, p -> MetalPipeline.compile(p, defaultShaderSource));
    }

    @Override
    public void clearPipelineCache() {
        pipelines.values().forEach(MetalPipeline::close);
        pipelines.clear();
    }

    @Override
    public List<String> getEnabledExtensions() {
        return List.of();
    }

    @Override
    public int getMaxSupportedAnisotropy() {
        return MAX_ANISOTROPY;
    }

    @Override
    public void close() {
        clearPipelineCache();
    }
}
