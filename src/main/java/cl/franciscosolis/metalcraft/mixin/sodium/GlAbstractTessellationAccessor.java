package cl.franciscosolis.metalcraft.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gl.tessellation.GlAbstractTessellation;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import net.caffeinemc.mods.sodium.client.gl.tessellation.TessellationBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GlAbstractTessellation.class, remap = false)
public interface GlAbstractTessellationAccessor {
    @Accessor("bindings")
    TessellationBinding[] metalcraft$bindings();

    @Accessor("primitiveType")
    GlPrimitiveType metalcraft$primitiveType();
}
