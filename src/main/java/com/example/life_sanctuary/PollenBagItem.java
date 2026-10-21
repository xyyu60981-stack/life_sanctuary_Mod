package com.example.life_sanctuary;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** One item type for empty and species-filled pollen bags. */
public final class PollenBagItem extends Item {
    public PollenBagItem(Properties properties) {
        super(properties);
    }

    /** Returns the pollen species label, or {@code null} for an empty bag. */
    public static String species(ItemStack stack) {
        return SpeciesTaggedItemData.read(stack);
    }

    /** Creates a filled pollen bag while keeping the same item ID. */
    public static ItemStack filled(String speciesId) {
        ItemStack stack = new ItemStack(LifeSanctuaryMod.POLLEN_BAG.get());
        SpeciesTaggedItemData.write(stack, speciesId);
        return stack;
    }

    /** Pollen bags consume one item only when fertilization succeeds. */
    public static boolean consumesOnAcceptedUse() {
        return true;
    }
}
