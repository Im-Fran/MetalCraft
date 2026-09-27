package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import java.nio.FloatBuffer;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformBlock;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformBool;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Every glUniform* Sodium issues, redirected into the current program's CPU-side uniform blocks. */
final class GlUniformsMixin {
    private GlUniformsMixin() {}

    @Mixin(value = {GlUniformInt.class, GlUniformBool.class}, remap = false)
    abstract static class Int {
        @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform1i(II)V"))
        private void metalcraft$set(int location, int value) {
            SodiumMetal.uniformInt(location, value);
        }
    }

    @Mixin(value = GlUniformFloat.class, remap = false)
    abstract static class Float1 {
        @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform1f(IF)V"))
        private void metalcraft$set(int location, float x) {
            SodiumMetal.uniformFloats(location, x);
        }
    }

    @Mixin(value = GlUniformFloat2v.class, remap = false)
    abstract static class Float2 {
        @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform2f(IFF)V"))
        private void metalcraft$set(int location, float x, float y) {
            SodiumMetal.uniformFloats(location, x, y);
        }

        @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform2fv(I[F)V"))
        private void metalcraft$setArray(int location, float[] v) {
            SodiumMetal.uniformFloats(location, v);
        }
    }

    @Mixin(value = GlUniformFloat3v.class, remap = false)
    abstract static class Float3 {
        @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform3f(IFFF)V"))
        private void metalcraft$set(int location, float x, float y, float z) {
            SodiumMetal.uniformFloats(location, x, y, z);
        }

        @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform3fv(I[F)V"))
        private void metalcraft$setArray(int location, float[] v) {
            SodiumMetal.uniformFloats(location, v);
        }
    }

    @Mixin(value = GlUniformFloat4v.class, remap = false)
    abstract static class Float4 {
        @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform4f(IFFFF)V"))
        private void metalcraft$set(int location, float x, float y, float z, float w) {
            SodiumMetal.uniformFloats(location, x, y, z, w);
        }

        @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform4fv(I[F)V"))
        private void metalcraft$setArray(int location, float[] v) {
            SodiumMetal.uniformFloats(location, v);
        }
    }

    @Mixin(value = GlUniformMatrix4f.class, remap = false)
    abstract static class Matrix4 {
        @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniformMatrix4fv(IZLjava/nio/FloatBuffer;)V"))
        private void metalcraft$set(int location, boolean transpose, FloatBuffer value) {
            SodiumMetal.uniformMatrix4(location, value);
        }
    }

    @Mixin(value = GlUniformBlock.class, remap = false)
    abstract static class Block {
        @Redirect(method = "bindBuffer", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glBindBufferBase(III)V"))
        private void metalcraft$bind(int target, int binding, int buffer) {
            SodiumMetal.bindUniformBuffer(binding, buffer);
        }
    }
}
