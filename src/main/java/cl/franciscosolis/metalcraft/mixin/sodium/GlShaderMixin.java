package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Shaders only record their source; translation to MSL happens at link time, when both stages are known. */
@Mixin(value = GlShader.class, remap = false)
abstract class GlShaderMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glCreateShader(I)I"))
    private int metalcraft$create(int type) {
        return SodiumMetal.createShader(type);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderWorkarounds;safeShaderSource(ILjava/lang/CharSequence;)V"))
    private void metalcraft$source(int shader, CharSequence source) {
        SodiumMetal.shaderSource(shader, source);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glCompileShader(I)V"))
    private void metalcraft$compile(int shader) {}

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glGetShaderInfoLog(I)Ljava/lang/String;"))
    private String metalcraft$log(int shader) {
        return "";
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;glGetShaderi(II)I"))
    private int metalcraft$status(int shader, int pname) {
        return 1;
    }

    @Redirect(method = "delete", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glDeleteShader(I)V"))
    private void metalcraft$delete(int shader) {
        SodiumMetal.deleteShader(shader);
    }
}
