package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Species-specific effect invoked when a tagged spore bag is used. */
@FunctionalInterface
public interface SporeBagEffect {
    /** Attempts to create that species' gametophyte on the target surface. */
    ReproductiveBagUseResult use(ServerLevel level, BlockPos target, String speciesId);
}
