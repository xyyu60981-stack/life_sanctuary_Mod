package com.example.life_sanctuary;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** One collector + one diamond. Previewing the result must never mutate the input. */
public final class CollectorUpgradeRecipe extends CustomRecipe {
    public CollectorUpgradeRecipe(CraftingBookCategory category) { super(category); }

    private static ItemStack findCollector(CraftingInput input) {
        ItemStack collector = ItemStack.EMPTY;
        boolean diamond = false;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.is(LifeSanctuaryMod.PORTABLE_COLLECTOR.get())) {
                if (!collector.isEmpty() || stack.getCount() != 1) return ItemStack.EMPTY;
                collector = stack;
            } else if (stack.is(Items.DIAMOND) && !diamond) {
                diamond = true;
            } else return ItemStack.EMPTY;
        }
        if (!diamond || collector.isEmpty()) return ItemStack.EMPTY;
        // Do not produce a box with inaccessible slots from malformed command-created data.
        if (collector.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).getSlots() > 54)
            return ItemStack.EMPTY;
        return collector;
    }

    @Override public boolean matches(CraftingInput input, Level level) {
        return !findCollector(input).isEmpty();
    }
    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack collector = findCollector(input);
        // Copies the entire component patch, including container contents, name and future metadata.
        return collector.transmuteCopy(LifeSanctuaryMod.PORTABLE_COLLECTOR_PLUS.get(), 1);
    }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 2; }
    @Override public RecipeSerializer<?> getSerializer() { return LifeSanctuaryMod.COLLECTOR_UPGRADE.get(); }
}
