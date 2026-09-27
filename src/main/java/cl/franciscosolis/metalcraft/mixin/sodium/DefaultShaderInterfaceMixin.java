package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderTextureSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultShaderInterface.class, remap = false)
abstract class DefaultShaderInterfaceMixin {
    @Shadow @Final private Map<ChunkShaderTextureSlot, GlUniformInt> uniformTextures;

    /** Sodium binds Minecraft's GL texture objects here; bind the Metal ones to the same texture unit instead. */
    @Inject(method = "bindTexture", at = @At("HEAD"), cancellable = true)
    private void metalcraft$bindTexture(ChunkShaderTextureSlot slot, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
        SodiumMetal.bindTexture(slot.ordinal(), view, sampler);
        uniformTextures.get(slot).setInt(slot.ordinal());
        ci.cancel();
    }
}
