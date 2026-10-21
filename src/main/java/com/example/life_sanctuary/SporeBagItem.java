package com.example.life_sanctuary;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** One item type for empty and species-filled spore bags. */
public final class SporeBagItem extends Item {
    public SporeBagItem(Properties properties) {
        super(properties);
    }

    /** Returns the species label, or {@code null} for an empty bag. */
    public static String species(ItemStack stack) {
        return SpeciesTaggedItemData.read(stack);
    }

    /** Writes the species label into a copy while preserving the same item ID. */
    public static ItemStack filled(String speciesId) {
        ItemStack stack = new ItemStack(LifeSanctuaryMod.SPORE_BAG.get());
        SpeciesTaggedItemData.write(stack, speciesId);
        return stack;
    }

    /** Spore bags consume one item only when the species effect accepts the target. */
    public static boolean consumesOnAcceptedUse() {
        return true;
    }
}
