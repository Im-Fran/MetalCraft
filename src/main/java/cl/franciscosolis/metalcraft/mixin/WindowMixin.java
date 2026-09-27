package cl.franciscosolis.metalcraft.mixin;

import cl.franciscosolis.metalcraft.MetalCraft;
import cl.franciscosolis.metalcraft.mtl.Mtl;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Window.class)
abstract class WindowMixin {
    /** Create the window without an OpenGL context; Metal renders into a CAMetalLayer instead. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V", remap = false))
    private void metalcraft$noGlContext(int hint, int value) {
        GLFW.glfwWindowHint(hint, hint == GLFW.GLFW_CLIENT_API && !MetalCraft.DISABLED ? GLFW.GLFW_NO_API : value);
    }

    @Redirect(method = "updateVsync", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapInterval(I)V", remap = false))
    private void metalcraft$vsync(int interval) {
        if (MetalCraft.DISABLED) GLFW.glfwSwapInterval(interval);
        else Mtl.setVsync(interval != 0);
    }
}
