package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;

/** Centralized environment classification; species decide which results matter to them. */
public final class PlantEnvironment {
    private PlantEnvironment() {}

    public static boolean isDry(LevelReader level, BlockPos pos) {
        var biome = level.getBiome(pos);
        if (biome.is(LifeSanctuaryMod.DRY_BIOMES)) return true;
        if (biome.is(LifeSanctuaryMod.WET_BIOMES)) return false;
        Biome.ClimateSettings climate = biome.value().getModifiedClimateSettings();
        return !climate.hasPrecipitation() || climate.downfall() <= 0.0F;
    }

    public static boolean isCold(LevelReader level, BlockPos pos) {
        var biome = level.getBiome(pos);
        if (biome.is(LifeSanctuaryMod.COLD_BIOMES)) return true;
        if (biome.is(LifeSanctuaryMod.HOT_BIOMES)) return false;
        return biome.value().coldEnoughToSnow(pos);
    }

    public static boolean isHot(LevelReader level, BlockPos pos) {
        var biome = level.getBiome(pos);
        if (biome.is(LifeSanctuaryMod.HOT_BIOMES)) return true;
        if (biome.is(LifeSanctuaryMod.COLD_BIOMES)) return false;
        return biome.value().getBaseTemperature() >= 2.0F;
    }
}
