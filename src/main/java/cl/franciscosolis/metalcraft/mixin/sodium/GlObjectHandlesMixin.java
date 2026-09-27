package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GlBuffer.class, remap = false)
abstract class GlObjectHandlesMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glGenBuffers()I"))
    private int metalcraft$genBuffer() {
        return SodiumMetal.genBuffer();
    }
}
