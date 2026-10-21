package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/** Plans one natural population inside a chunk after biome and spacing checks. */
public final class NaturalPopulationPlanner {
    private NaturalPopulationPlanner() {}

    public static List<BlockPos> plan(ServerLevel level, int chunkX, int chunkZ, String speciesId,
            NaturalPopulationRules rules, Collection<BlockPos> existingCenters,
            Predicate<ServerLevel> speciesPoolContainsSpecies,
            Predicate<BlockPos> legalBase, RandomSource random) {
        return plan(level, chunkX, chunkZ, speciesId, rules, existingCenters,
                speciesPoolContainsSpecies, legalBase, NaturalPopulationCondition.ALWAYS, random);
    }

    public static List<BlockPos> plan(ServerLevel level, int chunkX, int chunkZ, String speciesId,
            NaturalPopulationRules rules, Collection<BlockPos> existingCenters,
            Predicate<ServerLevel> speciesPoolContainsSpecies, Predicate<BlockPos> legalBase,
            NaturalPopulationCondition extraCondition, NaturalPopulationCondition preferredCondition,
            RandomSource random) {
        List<BlockPos> result = plan(level, chunkX, chunkZ, speciesId, rules, existingCenters,
                speciesPoolContainsSpecies, legalBase, extraCondition, random);
        if (result.isEmpty()) return result;
        List<BlockPos> preferred = result.stream().filter(pos -> preferredCondition.test(level, pos)).toList();
        int preferredLimit = Math.min(preferred.size(), Math.max(1, (int) Math.floor(result.size() * 0.80D)));
        if (preferredLimit == result.size()) return result;
        java.util.ArrayList<BlockPos> ordered = new java.util.ArrayList<>(preferred.subList(0, preferredLimit));
        result.stream().filter(pos -> !preferred.contains(pos)).limit(result.size() - ordered.size()).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    public static List<BlockPos> plan(ServerLevel level, int chunkX, int chunkZ, String speciesId,
            NaturalPopulationRules rules, Collection<BlockPos> existingCenters,
            Predicate<ServerLevel> speciesPoolContainsSpecies,
            Predicate<BlockPos> legalBase, NaturalPopulationCondition extraCondition, RandomSource random) {
        if (!speciesPoolContainsSpecies.test(level)) return List.of();
        BlockPos center = new BlockPos((chunkX << 4) + 8, level.getMinBuildHeight(), (chunkZ << 4) + 8);
        int spacingSquared = rules.minimumSpacing() * rules.minimumSpacing();
        for (BlockPos existing : existingCenters) {
            long dx = existing.getX() - center.getX();
            long dz = existing.getZ() - center.getZ();
            if (dx * dx + dz * dz < spacingSquared) return List.of();
        }
        if (random.nextFloat() >= rules.confirmationChance()) return List.of();

        List<BlockPos> legal = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            int x = (chunkX << 4) + random.nextInt(16);
            int z = (chunkZ << 4) + random.nextInt(16);
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos base = new BlockPos(x, y - 1, z);
            if (!level.getBlockState(base).is(ExampleMod.NATURAL_PLANT_EXCLUSION)
                    && !level.getBlockState(base.above()).is(ExampleMod.NATURAL_PLANT_EXCLUSION)
                    && legalBase.test(base) && extraCondition.test(level, base) && !legal.contains(base)) {
                legal.add(base);
            }
        }
        if (legal.isEmpty()) return List.of();
        Collections.shuffle(legal, new java.util.Random(random.nextLong()));
        int count = 1 + random.nextInt(rules.naturalSpawnLimit());
        return List.copyOf(legal.subList(0, Math.min(count, legal.size())));
    }
}
