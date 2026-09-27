package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
abstract class ShaderChunkRendererMixin {
    @Shadow @Final protected ChunkVertexType vertexType;
    @Shadow protected GlProgram<ChunkShaderInterface> activeProgram;

    @Shadow protected abstract GlProgram<ChunkShaderInterface> compileProgram(ChunkShaderOptions options);

    /** Same as Sodium's begin(), but opens a Metal render pass on the target instead of binding its GL framebuffer. */
    @Inject(method = "begin", at = @At("HEAD"), cancellable = true)
    private void metalcraft$begin(TerrainRenderPass pass, FogParameters parameters, GpuSampler terrainSampler, CallbackInfo ci) {
        SodiumMetal.beginPass(pass.getTarget(), pass.getPipeline());
        activeProgram = compileProgram(new ChunkShaderOptions(ChunkFogMode.SMOOTH, pass, vertexType));
        activeProgram.bind();
        activeProgram.getInterface().setupState(pass, parameters, terrainSampler);
        ci.cancel();
    }

    @Inject(method = "end", at = @At("TAIL"))
    private void metalcraft$end(TerrainRenderPass pass, CallbackInfo ci) {
        SodiumMetal.endPass();
    }
}
