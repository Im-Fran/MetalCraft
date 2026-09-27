package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GlProgram.class, remap = false)
abstract class GlProgramMixin {
    @Redirect(method = {"bind", "unbind"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glUseProgram(I)V"))
    private void metalcraft$use(int program) {
        SodiumMetal.useProgram(program);
    }

    @Redirect(method = "delete", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glDeleteProgram(I)V"))
    private void metalcraft$delete(int program) {
        SodiumMetal.deleteProgram(program);
    }

    @Redirect(method = {"bindUniform", "bindUniformOptional"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glGetUniformLocation(ILjava/lang/CharSequence;)I"))
    private int metalcraft$uniformLocation(int program, CharSequence name) {
        return SodiumMetal.uniformLocation(program, name);
    }

    @Redirect(method = {"bindUniformBlock", "bindUniformBlockOptional"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glGetUniformBlockIndex(ILjava/lang/CharSequence;)I"))
    private int metalcraft$blockIndex(int program, CharSequence name) {
        return SodiumMetal.uniformBlockIndex(program, name);
    }

    @Redirect(method = {"bindUniformBlock", "bindUniformBlockOptional"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glUniformBlockBinding(III)V"))
    private void metalcraft$blockBinding(int program, int index, int binding) {
        SodiumMetal.uniformBlockBinding(program, index, binding);
    }
}
