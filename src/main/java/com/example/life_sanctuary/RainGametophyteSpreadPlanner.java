package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Rain-triggered spread scan. Every reproductive individual contributes its
 * base block as a search center, but one population attempt creates only 1-2
 * offspring in total.
 */
public final class RainGametophyteSpreadPlanner {
    private RainGametophyteSpreadPlanner() {}

    public static List<BlockPos> findTargets(ServerLevel level, Iterable<BlockPos> reproductiveBases,
            int existingCount, RandomSource random, Predicate<BlockPos> substrate) {
        if (existingCount >= GametophyteSpreadRules.POPULATION_LIMIT) return List.of();
        int wanted = Math.min(GametophyteSpreadRules.POPULATION_LIMIT - existingCount,
                GametophyteSpreadRules.MIN_OFFSPRING
                        + random.nextInt(GametophyteSpreadRules.MAX_OFFSPRING - GametophyteSpreadRules.MIN_OFFSPRING + 1));
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos center : reproductiveBases) {
            if (!level.getChunkSource().hasChunk(center.getX() >> 4, center.getZ() >> 4)) continue;
            for (int dx = -GametophyteSpreadRules.HORIZONTAL_RADIUS; dx <= GametophyteSpreadRules.HORIZONTAL_RADIUS; dx++) {
                for (int dz = -GametophyteSpreadRules.HORIZONTAL_RADIUS; dz <= GametophyteSpreadRules.HORIZONTAL_RADIUS; dz++) {
                    for (int dy = -GametophyteSpreadRules.VERTICAL_RADIUS; dy <= GametophyteSpreadRules.VERTICAL_RADIUS; dy++) {
                        BlockPos base = center.offset(dx, dy, dz);
                        BlockState below = level.getBlockState(base);
                        BlockState above = level.getBlockState(base.above());
                        if (substrate.test(base) && below.isSolidRender(level, base)
                                && above.canBeReplaced() && !candidates.contains(base.above())) {
                            candidates.add(base.above().immutable());
                        }
                    }
                }
            }
        }
        Collections.shuffle(candidates, new java.util.Random(random.nextLong()));
        return List.copyOf(candidates.subList(0, Math.min(wanted, candidates.size())));
    }
}
