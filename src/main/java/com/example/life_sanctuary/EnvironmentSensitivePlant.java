package com.example.life_sanctuary;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Contract for plants whose survival depends on their environment.
 *
 * Species expose their current response, including pending death, so growth,
 * bonemeal and future tooltip providers can share the same decision.
 */
public interface EnvironmentSensitivePlant {
    /** Returns whether the current position is suitable for this plant. */
    default boolean isEnvironmentSuitable(LevelReader level, BlockPos pos, BlockState state) {
        return true;
    }

    /** Override for species with climate-triggered withering/disappearance timers. */
    default PlantClimateResponse climateResponse(LevelReader level, BlockPos pos, BlockState state) {
        return isEnvironmentSuitable(level, pos, state)
                ? PlantClimateResponse.GROWING : PlantClimateResponse.PAUSED;
    }

    /** Must be checked in isValidBonemealTarget, before vanilla consumes the item. */
    default boolean allowsBonemealInClimate(LevelReader level, BlockPos pos, BlockState state) {
        return climateResponse(level, pos, state).allowsBonemeal();
    }

    /** Returns the dead-state replacement when the plant should wither. */
    default Optional<BlockState> deadVariant(LevelReader level, BlockPos pos, BlockState state) {
        return Optional.empty();
    }
}
