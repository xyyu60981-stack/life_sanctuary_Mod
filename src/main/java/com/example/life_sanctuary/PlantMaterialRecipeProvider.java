package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.SingleItemRecipeBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.data.DataMapProvider;
import net.neoforged.neoforge.registries.datamaps.builtin.Compostable;
import net.neoforged.neoforge.registries.datamaps.builtin.FurnaceFuel;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;

/** Datagen only. Activate after all seven actual materials are registered. */
public final class PlantMaterialRecipeProvider extends RecipeProvider {
    private final CompletableFuture<HolderLookup.Provider> registries;
    private final PlantMaterialRecipes.Materials materials;
    public PlantMaterialRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries,
            PlantMaterialRecipes.Materials materials) {
        super(output, registries);
        this.registries = registries; this.materials = java.util.Objects.requireNonNull(materials);
    }
    @Override protected void buildRecipes(RecipeOutput output) {
        var resolved = resolve(registries.join(), materials);
        for (var recipe : PlantMaterialRecipes.plan(materials)) {
            var input = resolved.get(recipe.ingredient());
            var result = resolved.get(recipe.result());
            var criterion = InventoryChangeTrigger.TriggerInstance.hasItems(input);
            if (recipe.method() == PlantMaterialRecipes.Method.STONECUTTING)
                SingleItemRecipeBuilder.stonecutting(Ingredient.of(input), RecipeCategory.DECORATIONS, result, recipe.outputCount())
                        .unlockedBy("has_material", criterion).save(output, recipe.id());
            else {
                // Four corners require a real 3x3 crafting table, not a four-item stonecutter input.
                if (recipe.inputCount() != 4 || recipe.outputCount() != 1)
                    throw new IllegalArgumentException("Unsupported dead-stem workbench ratio");
                var builder = ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, result);
                PlantMaterialRecipes.DEAD_LOG_WORKBENCH_PATTERN.forEach(builder::pattern);
                builder.define('S', input)
                        .unlockedBy("has_material", criterion).save(output, recipe.id());
            }
        }
    }

    /** Contribute to ONE project-wide data-map provider, preserving peat and other existing entries. */
    public static void contributeItemData(DataMapProvider provider, HolderLookup.Provider registries,
            PlantMaterialRecipes.Materials materials) {
        resolve(registries, materials); // Fail before contributing partial data for missing materials.
        for (var kind : PlantMaterialRecipes.Mat.values()) {
            var id = materials.mats().get(kind);
            provider.builder(NeoForgeDataMaps.FURNACE_FUELS).add(id, new FurnaceFuel(PlantMaterialRecipes.MAT_BURN_TICKS), false);
            provider.builder(NeoForgeDataMaps.COMPOSTABLES).add(id, new Compostable(PlantMaterialRecipes.MAT_COMPOST_CHANCE), false);
        }
    }

    private static Map<ResourceLocation, Item> resolve(HolderLookup.Provider registries, PlantMaterialRecipes.Materials materials) {
        var items = registries.lookupOrThrow(Registries.ITEM);
        var result = new HashMap<ResourceLocation, Item>();
        for (var recipe : PlantMaterialRecipes.plan(materials)) {
            for (var id : java.util.List.of(recipe.ingredient(), recipe.result())) {
                var item = items.getOrThrow(ResourceKey.create(Registries.ITEM, id)).value();
                if (item == Items.AIR) throw new IllegalArgumentException("Missing material " + id);
                result.put(id, item);
            }
            if (new ItemStack(result.get(recipe.result())).getMaxStackSize() < recipe.outputCount())
                throw new IllegalArgumentException("Material output exceeds stack capacity " + recipe.result());
        }
        return Map.copyOf(result);
    }
}
