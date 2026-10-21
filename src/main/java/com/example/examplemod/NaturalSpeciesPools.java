package com.example.examplemod;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Registry seam for biome-to-species pools from the design document. */
public final class NaturalSpeciesPools {
    private NaturalSpeciesPools() {}

    public static List<NaturalSpeciesGenerator> forBiome(ServerLevel level, LevelChunk chunk) {
        return List.of();
    }

    @FunctionalInterface
    public interface NaturalSpeciesGenerator {
        void generate(ServerLevel level, LevelChunk chunk);
    }
}
