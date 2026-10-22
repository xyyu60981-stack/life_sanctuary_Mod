package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/** Species adapter: special connected plants can override the eligible-base check. */
public interface PeatResponsivePlant {
    PeatPlantInteraction.Bonus peatBonus();

    /** Called at maturation, never every tick. Override for multi-base or connected structures. */
    default boolean hasEligiblePeat(LevelReader level, BlockPos pos, BlockState state) {
        return level.getBlockState(pos.below()).is(PeatPlantInteraction.PEAT)
                && state.canSurvive(level, pos);
    }
}
