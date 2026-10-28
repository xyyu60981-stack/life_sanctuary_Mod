package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Implement only on stages requiring nearby water. Ranges default to the supporting block. */
public interface WaterDependentPlant {
    /** Half-extents: (2, 1) means XYZ 5x3x5; (4, 1) means XYZ 9x3x9. */
    record Requirement(int horizontalRadius, int verticalRadius, int dryTicks) {
        public Requirement {
            if (horizontalRadius < 0 || horizontalRadius > 16 || verticalRadius < 0
                    || verticalRadius > 16 || dryTicks <= 0) throw new IllegalArgumentException("Invalid water requirement");
        }
    }

    default Requirement waterRequirement(BlockState state) {
        throw new UnsupportedOperationException("This plant requires a position-aware water query");
    }

    default java.util.Optional<Requirement> waterRequirement(ServerLevel level, BlockPos pos, BlockState state) {
        return java.util.Optional.of(waterRequirement(state));
    }

    default void waterStatusChanged(ServerLevel level, BlockPos pos, BlockState state, boolean dry) {}

    /** Multi-block or epiphytic plants may override this to return their actual supporting base. */
    default BlockPos waterSearchCenter(BlockPos pos, BlockState state) {
        return pos.below();
    }

    /** Return true only after successfully replacing/removing the plant. */
    boolean finishWaterDecline(ServerLevel level, BlockPos pos, BlockState state);
}
