package com.example.life_sanctuary;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;

/**
 * Finds spread positions using the plant's base block as the only search center.
 * The fixed local search is intentional: farm size must not turn a plant tick
 * into a scan of every member in a population or every loaded chunk.
 */
public final class HorsetailSpreadPlanner {
    private HorsetailSpreadPlanner() {}

    public static Optional<BlockPos> findTarget(LevelReader level, BlockPos base) {
        for (BlockPos candidateBase : directCandidates(base)) {
            if (isLegalTarget(level, candidateBase)) return Optional.of(candidateBase.above());
        }
        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos candidateBase = base.offset(x, y, z);
                    if (isLegalTarget(level, candidateBase) && level.getBlockState(candidateBase).isSolidRender(level, candidateBase)) {
                        return Optional.of(candidateBase.above());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Iterable<BlockPos> directCandidates(BlockPos base) {
        return java.util.List.of(base.east(), base.west(), base.south(), base.north());
    }

    private static boolean isLegalTarget(LevelReader level, BlockPos candidateBase) {
        var substrate = level.getBlockState(candidateBase);
        return substrate.is(LifeSanctuaryMod.HORSETAIL_SUBSTRATE)
                && level.getBlockState(candidateBase.above()).canBeReplaced();
    }
}
