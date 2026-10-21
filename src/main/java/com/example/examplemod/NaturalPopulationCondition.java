package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Optional species-specific gate for natural population placement. */
@FunctionalInterface
public interface NaturalPopulationCondition {
    boolean test(ServerLevel level, BlockPos base);

    NaturalPopulationCondition ALWAYS = (level, base) -> true;
}
