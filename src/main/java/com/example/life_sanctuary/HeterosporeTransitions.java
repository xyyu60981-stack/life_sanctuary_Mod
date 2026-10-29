package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** Auxiliary gametophyte deadlines enter the existing atomic structure transactions here. */
public final class HeterosporeTransitions {
    private HeterosporeTransitions() {}

    /** Ordinary species retain their timer rules; registered differentiated gametophytes require callback authority. */
    static boolean stageReady(ServerLevel level, PlantIndividualState expected) {
        if (SharedGametophyteWater.monitoringUnavailable(level, expected)) return false;
        var adapter = HeterosporeAdapters.find(expected.species());
        if (adapter != null && adapter.gametophyteStage().equals(expected.stage()))
            return HeterosporeWorldData.get(level).authorizes(level, expected, HeterosporeCycle.Action.SEEDLING);
        return PlantLifecycle.canRunTimer(expected) && expected.remainingTicks() == 0;
    }

    /** Species supplies the real seedling shape, substrate observations and initial resources. */
    public static HeterosporeAdapters.Result grow(ServerLevel level, PlantIndividualState expected,
            PlantStageStructure target, Map<BlockPos, BlockState> observed, PlantStageResources resources) {
        if (!HeterosporeWorldData.get(level).authorizes(level, expected, HeterosporeCycle.Action.SEEDLING))
            return HeterosporeAdapters.Result.BLOCKED;
        return result(PlantStageGrowth.advance(level, expected, target, observed, resources, level.random));
    }

    /** No drops. Capture only this individual's parts, after checking every required chunk is already ticking. */
    public static HeterosporeAdapters.Result disappear(ServerLevel level, PlantIndividualState expected) {
        if (!HeterosporeWorldData.get(level).authorizes(level, expected, HeterosporeCycle.Action.DISAPPEAR))
            return HeterosporeAdapters.Result.BLOCKED;
        var owned = PlantIndividuals.get(level).partsOf(expected.id());
        if (owned.isEmpty() || !owned.containsKey(expected.base())) return HeterosporeAdapters.Result.RECOVERY_REQUIRED;
        for (var pos : owned.keySet()) {
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    || !level.getChunkSource().isPositionTicking(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)))
                return HeterosporeAdapters.Result.BLOCKED;
        }
        var observed = new LinkedHashMap<BlockPos, BlockState>();
        for (var pos : owned.keySet()) {
            var state = level.getBlockState(pos);
            if (state.isAir()) return HeterosporeAdapters.Result.RECOVERY_REQUIRED;
            observed.put(pos, state);
        }
        return result(PlantStructureRemoval.removeIndividual(level, expected, observed).commit());
    }
    static HeterosporeAdapters.Result result(PlantStructureCommitter.Result result) {
        return switch (result.status()) {
            case COMMITTED -> HeterosporeAdapters.Result.APPLIED;
            case REJECTED -> HeterosporeAdapters.Result.BLOCKED;
            case ROLLED_BACK, RECOVERY_REQUIRED -> HeterosporeAdapters.Result.RECOVERY_REQUIRED;
        };
    }
}
