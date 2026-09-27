package cl.franciscosolis.metalcraft.mixin.sodium;

import cl.franciscosolis.metalcraft.compat.sodium.SodiumMetal;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlIndexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice$ImmediateDrawCommandList", remap = false)
abstract class ImmediateDrawCommandListMixin {
    /** @author MetalCraft @reason glMultiDrawElementsBaseVertex -> Metal indexed draws */
    @Overwrite
    public void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlIndexType indexType) {
        var tessellation = (GlAbstractTessellationAccessor) ((GLRenderDeviceAccessor) RenderDevice.INSTANCE).metalcraft$activeTessellation();
        SodiumMetal.multiDraw(tessellation.metalcraft$primitiveType(), tessellation.metalcraft$bindings(), batch, indexType);
    }
}
