package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/** Bounded, read-only candidate planner for the later biome population placement adapter. */
public final class LargeLeafMossNatural {
    private LargeLeafMossNatural() {}
    public static final TagKey<Biome> BIOMES = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "large_leaf_moss_biomes"));
    public static Map<BlockPos, Integer> plan(ServerLevel level, ChunkPos region, RandomSource random) {
        if (!level.getServer().isSameThread() || !level.getChunkSource().hasChunk(region.x, region.z)) return Map.of();
        var store = PlantIndividuals.get(level); if (store.isReadOnly()) return Map.of();
        int occupied = PlantSpeciesRegistry.spreadRule(LargeLeafMossRules.SPECIES).orElseThrow().occupied(
                store.populationCounts(LargeLeafMossRules.SPECIES, LargeLeafMossRules.population(region.getWorldPosition())));
        int capacity = LargeLeafMossRules.LIMIT - occupied;
        if (capacity < 4) return Map.of();
        var positions = new java.util.LinkedHashSet<BlockPos>();
        for (int attempt = 0; attempt < 64; attempt++) {
            int x = region.getMinBlockX() + random.nextInt(16), z = region.getMinBlockZ() + random.nextInt(16);
            var pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!level.isOutsideBuildHeight(pos) && level.getBiome(pos).is(BIOMES) && level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.below()).is(BlockTags.DIRT) && !level.getBlockState(pos.below()).is(LifeSanctuaryMod.NATURAL_PLANT_EXCLUSION)
                    && store.atBase(pos).isEmpty() && store.atPart(pos).isEmpty()) positions.add(pos);
        }
        if (positions.size() < 4) return Map.of();
        int count = Math.min(Math.min(4 + random.nextInt(5), capacity), positions.size());
        var result = new LinkedHashMap<BlockPos, Integer>();
        for (var pos : positions) { if (result.size() == count) break; result.put(pos, LargeLeafMossRules.naturalDensity(random.nextInt(100))); }
        return java.util.Collections.unmodifiableMap(result);
    }
}
