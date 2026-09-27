package cl.franciscosolis.metalcraft.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GLRenderDevice.class, remap = false)
public interface GLRenderDeviceAccessor {
    @Accessor("activeTessellation")
    GlTessellation metalcraft$activeTessellation();
}
