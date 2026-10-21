package com.example.life_sanctuary;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Contract for plants whose survival depends on their environment.
 *
 * The current project has not yet defined biome/climate tags or death timers,
 * so this interface intentionally has no active behavior. Plant blocks can
 * implement it once those rules are settled without changing the lifecycle API.
 */
public interface EnvironmentSensitivePlant {
    /** Returns whether the current position is suitable for this plant. */
    default boolean isEnvironmentSuitable(LevelReader level, BlockPos pos, BlockState state) {
        return true;
    }

    /** Returns the dead-state replacement when the plant should wither. */
    default Optional<BlockState> deadVariant(LevelReader level, BlockPos pos, BlockState state) {
        return Optional.empty();
    }
}
