package com.example.life_sanctuary;

import javax.annotation.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Shared data-component access for items whose contents belong to a species. */
public final class SpeciesTaggedItemData {
    private static final String SPECIES = "Species";

    private SpeciesTaggedItemData() {}

    /** Reads the species label without mutating the stack. */
    @Nullable
    public static String read(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(SPECIES) ? tag.getString(SPECIES) : null;
    }

    /** Writes the species label into the supplied stack. */
    public static void write(ItemStack stack, String speciesId) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putString(SPECIES, speciesId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
