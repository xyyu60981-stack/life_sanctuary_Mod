package com.example.life_sanctuary;

import net.minecraft.world.item.ItemStack;

/** Shared bag classification; a species-labelled bag must never be consumed as an empty bag. */
public enum ReproductiveBagKind {
    SPORE, POLLEN;

    public boolean isEmptyBag(ItemStack stack) {
        if (stack.isEmpty() || SpeciesTaggedItemData.read(stack) != null) return false;
        if (this == SPORE && stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag().contains("SporeForm")) return false;
        return this == SPORE
                ? stack.is(LifeSanctuaryMod.EMPTY_SPORE_BAG.get()) || stack.is(LifeSanctuaryMod.SPORE_BAG.get())
                : stack.is(LifeSanctuaryMod.EMPTY_POLLEN_BAG.get()) || stack.is(LifeSanctuaryMod.POLLEN_BAG.get());
    }

    public boolean isFilledBag(ItemStack stack) {
        String species = SpeciesTaggedItemData.read(stack);
        return !stack.isEmpty() && species != null && !species.isBlank()
                && stack.is(this == SPORE ? LifeSanctuaryMod.SPORE_BAG.get() : LifeSanctuaryMod.POLLEN_BAG.get())
                && (this != SPORE || SporeBagContents.form(stack).isPresent());
    }
}
