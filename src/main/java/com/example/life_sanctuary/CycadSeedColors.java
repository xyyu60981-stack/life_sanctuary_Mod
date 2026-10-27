package com.example.life_sanctuary;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** Models tint only coat faces/layers at index 0; bare seed models use their original untinted material. */
public final class CycadSeedColors {
    private CycadSeedColors() {}
    public static void blocks(RegisterColorHandlersEvent.Block event) {
        for (var block : BuiltInRegistries.BLOCK) if (block instanceof CycadSeedAppearance appearance)
            event.register((state, world, pos, tint) -> tint == 0 && state.hasProperty(CycadSeedAppearance.SEED_COAT)
                    && state.getValue(CycadSeedAppearance.SEED_COAT) ? appearance.coatColor() : 0xFFFFFF, block);
    }
    public static void items(RegisterColorHandlersEvent.Item event) {
        for (var item : BuiltInRegistries.ITEM) if (item instanceof CycadSeedItem seed)
            event.register((stack, tint) -> tint == 0 && CycadSeedCoat.read(stack).orElse(false) ? seed.appearance().coatColor() : 0xFFFFFF, item);
    }
    public static void properties() {
        for (var item : BuiltInRegistries.ITEM) if (item instanceof CycadSeedItem)
            ItemProperties.register(item, ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "seed_coat"),
                    (stack, level, holder, seed) -> CycadSeedCoat.read(stack).orElse(false) ? 1 : 0);
    }
}
