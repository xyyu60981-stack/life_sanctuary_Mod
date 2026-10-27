package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Registered material IDs are provided by their author; no speculative item registrations. */
public final class PlantMaterialRecipes {
    public static final int MAT_YIELD = 3;
    public static final int MAT_BURN_TICKS = 300; // Three vanilla sticks, 100 ticks each.
    public static final float MAT_COMPOST_CHANCE = 0.30F;
    public static final List<String> DEAD_LOG_WORKBENCH_PATTERN = List.of("S S", "   ", "S S");
    private PlantMaterialRecipes() {}
    public enum Mat { SCATTERED_SPORE_BALLS, TWIGS, FERN_FRONDS, FALLEN_LEAVES }
    public enum Method { STONECUTTING, WORKBENCH }
    public record Materials(ResourceLocation stalkBundle, Map<Mat, ResourceLocation> mats,
            ResourceLocation deadLog, ResourceLocation deadStem) {
        public Materials {
            Objects.requireNonNull(stalkBundle); Objects.requireNonNull(deadLog); Objects.requireNonNull(deadStem);
            mats = Map.copyOf(mats);
            if (!mats.keySet().equals(Set.of(Mat.values()))) throw new IllegalArgumentException("Exactly four decoration mats required");
            var ids = new ArrayList<>(mats.values()); ids.add(stalkBundle); ids.add(deadLog); ids.add(deadStem);
            if (new HashSet<>(ids).size() != ids.size() || ids.stream().anyMatch(id -> id.equals(ResourceLocation.withDefaultNamespace("air"))))
                throw new IllegalArgumentException("Materials must be distinct real items");
        }
    }
    public record Recipe(ResourceLocation id, Method method, ResourceLocation ingredient, int inputCount,
            ResourceLocation result, int outputCount) {
        public Recipe {
            Objects.requireNonNull(id); Objects.requireNonNull(method); Objects.requireNonNull(ingredient); Objects.requireNonNull(result);
            if (inputCount <= 0 || outputCount <= 0 || method == Method.STONECUTTING && inputCount != 1)
                throw new IllegalArgumentException("Invalid material recipe count");
        }
    }
    /** Only the four confirmed stonecutter mats; no ambiguous bundle-only crafting recipes. */
    public static List<Recipe> plan(Materials materials) {
        var recipes = new ArrayList<Recipe>();
        for (Mat kind : Mat.values()) {
            var mat = materials.mats().get(kind);
            recipes.add(new Recipe(id(mat, "_from_stalk_bundle"), Method.STONECUTTING, materials.stalkBundle(), 1, mat, MAT_YIELD));
        }
        recipes.add(new Recipe(id(materials.deadStem(), "_from_dead_log"), Method.STONECUTTING,
                materials.deadLog(), 1, materials.deadStem(), 4));
        recipes.add(new Recipe(id(materials.deadLog(), "_from_four_dead_stems"), Method.WORKBENCH,
                materials.deadStem(), 4, materials.deadLog(), 1));
        return List.copyOf(recipes);
    }
    private static ResourceLocation id(ResourceLocation material, String suffix) {
        return ResourceLocation.fromNamespaceAndPath("life_sanctuary", "materials/" + material.getNamespace() + "/" + material.getPath() + suffix);
    }
}
