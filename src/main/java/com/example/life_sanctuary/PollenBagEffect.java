package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Species-specific effect invoked when a tagged pollen bag is used. */
@FunctionalInterface
public interface PollenBagEffect {
    /** Attempts to fertilize a compatible female or complete flower. */
    ReproductiveBagUseResult use(ServerLevel level, BlockPos target, String speciesId);
}
