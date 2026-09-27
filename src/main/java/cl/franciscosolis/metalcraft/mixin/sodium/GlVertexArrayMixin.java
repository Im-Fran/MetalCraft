package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import net.caffeinemc.mods.sodium.client.gl.array.GlVertexArray;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Vertex arrays carry no state on Metal (the vertex layout lives in the pipeline); only the handle is needed. */
@Mixin(value = GlVertexArray.class, remap = false)
abstract class GlVertexArrayMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glGenVertexArrays()I"))
    private int metalcraft$genVertexArray() {
        return SodiumMetal.newHandle();
    }
}
