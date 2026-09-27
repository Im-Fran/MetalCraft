package cl.franciscosolis.metalcraft.mixin;

import cl.franciscosolis.metalcraft.MetalCraft;
import cl.franciscosolis.metalcraft.NativeLoader;
import cl.franciscosolis.metalcraft.device.MetalDevice;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SamplerCache;
import net.minecraft.client.renderer.DynamicUniforms;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
abstract class RenderSystemMixin {
    @Shadow private static GpuDevice DEVICE;
    @Shadow private static String apiDescription;
    @Shadow private static DynamicUniforms dynamicUniforms;
    @Shadow private static SamplerCache samplerCache;

    /** Same as vanilla initRenderer, with MetalDevice in place of GlDevice. */
    @Inject(method = "initRenderer", at = @At("HEAD"), cancellable = true)
    private static void metalcraft$initMetal(long window, int debugVerbosity, boolean syncDebug, ShaderSource shaderSource, boolean debugLabels, CallbackInfo ci) {
        if (MetalCraft.DISABLED) return;
        NativeLoader.load();
        DEVICE = new MetalDevice(window, shaderSource);
        apiDescription = DEVICE.getImplementationInformation();
        dynamicUniforms = new DynamicUniforms();
        samplerCache.initialize();
        ci.cancel();
    }

    /** Frames are presented by MetalCommandEncoder.presentTexture; there's no GL back buffer to swap. */
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false))
    private static void metalcraft$noSwap(long window) {
        if (MetalCraft.DISABLED) GLFW.glfwSwapBuffers(window);
    }
}
