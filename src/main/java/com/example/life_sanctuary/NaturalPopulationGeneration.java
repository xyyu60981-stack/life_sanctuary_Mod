package com.example.life_sanctuary;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Data-driven generation coordinator. Species pools are intentionally kept
 * outside the chunk event so each biome can provide its own pool later.
 */
public final class NaturalPopulationGeneration {
    private NaturalPopulationGeneration() {}

    public static void generate(ServerLevel level, LevelChunk chunk) {
        // The biome/species registry is not populated until the custom biomes
        // are registered. An empty pool is therefore a cheap no-op here.
        NaturalSpeciesPools.forBiome(level, chunk).forEach(species -> species.generate(level, chunk));
    }
}
