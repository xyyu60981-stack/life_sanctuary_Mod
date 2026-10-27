package com.example.life_sanctuary;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** One occupied crafting slot, one seed consumed by vanilla crafting, no detached coat item. */
public final class CycadSeedPeelingRecipe extends CustomRecipe {
    public CycadSeedPeelingRecipe(CraftingBookCategory category) { super(category); }
    private static ItemStack source(CraftingInput input) {
        var source = ItemStack.EMPTY;
        for (int slot = 0; slot < input.size(); slot++) {
            var stack = input.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!source.isEmpty() || !CycadSeedCoat.read(stack).orElse(false)) return ItemStack.EMPTY;
            source = stack;
        }
        return source;
    }
    @Override public boolean matches(CraftingInput input, Level level) { return !source(input).isEmpty(); }
    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) { return CycadSeedCoat.peel(source(input)); }
    @Override public boolean canCraftInDimensions(int width, int height) { return width > 0 && height > 0; }
    @Override public NonNullList<ItemStack> getRemainingItems(CraftingInput input) { return NonNullList.withSize(input.size(), ItemStack.EMPTY); }
    @Override public RecipeSerializer<?> getSerializer() { return LifeSanctuaryMod.CYCAD_SEED_PEELING.get(); }
}
