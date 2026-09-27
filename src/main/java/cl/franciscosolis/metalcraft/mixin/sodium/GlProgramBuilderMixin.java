package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlProgram$Builder", remap = false)
abstract class GlProgramBuilderMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glCreateProgram()I"))
    private int metalcraft$create() {
        return SodiumMetal.createProgram();
    }

    @Redirect(method = "attachShader", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glAttachShader(II)V"))
    private void metalcraft$attach(int program, int shader) {
        SodiumMetal.attachShader(program, shader);
    }

    @Redirect(method = "bindAttribute", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glBindAttribLocation(IILjava/lang/CharSequence;)V"))
    private void metalcraft$bindAttribute(int program, int index, CharSequence name) {
        SodiumMetal.bindAttribLocation(program, index, name);
    }

    @Redirect(method = "bindFragmentData", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glBindFragDataLocation(IILjava/lang/CharSequence;)V"))
    private void metalcraft$bindFragData(int program, int index, CharSequence name) {}

    @Redirect(method = "link", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glLinkProgram(I)V"))
    private void metalcraft$link(int program) {
        SodiumMetal.link(program);
    }

    @Redirect(method = "link", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glGetProgramInfoLog(I)Ljava/lang/String;"))
    private String metalcraft$log(int program) {
        return SodiumMetal.programLog(program);
    }

    @Redirect(method = "link", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;glGetProgrami(II)I"))
    private int metalcraft$status(int program, int pname) {
        return SodiumMetal.linkStatus(program);
    }
}
