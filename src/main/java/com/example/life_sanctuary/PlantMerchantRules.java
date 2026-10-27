package com.example.life_sanctuary;

/** Spawn-time choice only: travelling into another biome never rerolls an existing trader. */
public final class PlantMerchantRules {
    private PlantMerchantRules() {}
    public static boolean choose(boolean sanctuaryBiome, boolean vanillaJungleOrSavanna, int roll) {
        if (roll < 0 || roll >= 100) throw new IllegalArgumentException("Expected a percentile roll");
        return sanctuaryBiome || vanillaJungleOrSavanna && roll < 30;
    }
}
