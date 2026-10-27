package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public final class MixedSporeRecipe extends CustomRecipe {
    public MixedSporeRecipe(CraftingBookCategory category) { super(category); }
    private static Optional<ResourceLocation> species(CraftingInput input) {
        SporeBagContents.Ingredient first = null, second = null;
        for (int slot = 0; slot < input.size(); slot++) {
            var stack = input.getItem(slot); if (stack.isEmpty()) continue;
            var form = SporeBagContents.form(stack); var species = PlantSporeTraits.species(SporeBagItem.species(stack));
            if (form.isEmpty() || species.isEmpty()) return Optional.empty();
            var ingredient = new SporeBagContents.Ingredient(species.get(), form.get());
            if (first == null) first = ingredient; else if (second == null) second = ingredient; else return Optional.empty();
        }
        return first == null || second == null ? Optional.empty() : SporeBagContents.mixedSpecies(first, second);
    }
    @Override public boolean matches(CraftingInput input, Level level) { return species(input).isPresent(); }
    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return species(input).map(species -> {
            var result = SporeBagItem.filled(species.getNamespace().equals(LifeSanctuaryMod.MODID) ? species.getPath() : species.toString(), SporeBagContents.Form.MIXED);
            result.setCount(2); return result;
        }).orElse(ItemStack.EMPTY);
    }
    @Override public boolean canCraftInDimensions(int width, int height) { return width > 0 && height > 0 && (width > 1 || height > 1); }
    @Override public NonNullList<ItemStack> getRemainingItems(CraftingInput input) { return NonNullList.withSize(input.size(), ItemStack.EMPTY); }
    @Override public RecipeSerializer<?> getSerializer() { return LifeSanctuaryMod.MIXED_SPORES.get(); }
}
