package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 已加载活动区块中的自然芦木整组一次放置；不是 WorldGenRegion Feature。 */
public final class RhizomeNaturalPlacement {
    private RhizomeNaturalPlacement() {}
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    public record Result(PlantStructureCommitter.Status status, Optional<RhizomeNaturalPreflight.Result> preflight) {}

    /**
     * 规划需要的区块应由调用者先确认可用，再领取稳定 rule/candidate 的一次凭据。
     * 生态拒绝、空间不足、写入失败也算本次尝试；不重新抽高度或排队重试。
     */
    public static Optional<Result> placeOnce(ServerLevel level, ResourceLocation rule, BlockPos candidate,
            Supplier<Optional<RhizomeNaturalStructurePlan>> planner) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        Objects.requireNonNull(planner);
        var individuals = PlantIndividuals.get(level);
        var systems = PlantRhizomeSystems.get(level);
        var ledger = NaturalPlantGenerationAttempts.get(level);
        if (individuals.isReadOnly() || systems.isReadOnly() || ledger.isReadOnly()
                || individuals.isApplyingStructure() || systems.isApplyingStructure())
            return Optional.of(new Result(PlantStructureCommitter.Status.REJECTED, Optional.empty()));
        if (!ledger.claim(rule, candidate)) return Optional.empty();
        return planner.get().map(plan -> placeSelected(level, plan));
    }

    private static Result placeSelected(ServerLevel level, RhizomeNaturalStructurePlan plan) {
        var preflight = RhizomeNaturalPreflight.check(level, plan);
        if (!preflight.ready()) return new Result(PlantStructureCommitter.Status.REJECTED, Optional.of(preflight));
        var checked = preflight.checked().orElseThrow();
        Map<BlockPos, BlockState> targets = targets(checked);
        var attempted = new ArrayList<BlockPos>();
        boolean committed = false;
        boolean restored = true;
        try (var session = RhizomeNaturalRegistration.open(level, checked)) {
            try {
                for (var entry : targets.entrySet()) {
                    var pos = entry.getKey();
                    var before = checked.expectedStates().get(pos);
                    if (!available(level, pos) || !level.getBlockState(pos).equals(before))
                        throw new IllegalStateException("Natural target changed at " + pos);
                    if (before.equals(entry.getValue())) continue;
                    attempted.add(pos); // 回调可能在改块之后抛错，必须先记录。
                    if (!level.setBlock(pos, entry.getValue(), FLAGS)
                            || !level.getBlockState(pos).equals(entry.getValue()))
                        throw new IllegalStateException("Natural write failed at " + pos);
                }
                if (!session.commit()) throw new IllegalStateException("Natural group changed before registration");
                committed = true;
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(RhizomeNaturalPlacement.class).error("Natural rhizome group placement failed", failure);
                for (int i = attempted.size() - 1; i >= 0; i--) {
                    var pos = attempted.get(i);
                    try {
                        if (!available(level, pos)) { restored = false; continue; }
                        var actual = level.getBlockState(pos);
                        var before = checked.expectedStates().get(pos);
                        if (actual.equals(before)) continue;
                        if (!actual.equals(targets.get(pos))) { restored = false; continue; }
                        level.setBlock(pos, before, FLAGS);
                    } catch (RuntimeException recoveryFailure) {
                        restored = false;
                        org.slf4j.LoggerFactory.getLogger(RhizomeNaturalPlacement.class).error("Cannot restore natural block at " + pos, recoveryFailure);
                    }
                }
                // 倒序恢复的回调也可能改变较早恢复的位置，最后完整复核本次尝试写入集合。
                for (var pos : attempted)
                    if (!matches(level, pos, checked.expectedStates().get(pos))) restored = false;
                if (!restored) session.retainForRecovery();
            }
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(RhizomeNaturalPlacement.class).error("Natural rhizome registration failed", failure);
            return new Result(attempted.isEmpty() ? PlantStructureCommitter.Status.REJECTED
                    : PlantStructureCommitter.Status.RECOVERY_REQUIRED, Optional.empty());
        }
        if (!committed) return new Result(restored ? PlantStructureCommitter.Status.ROLLED_BACK
                : PlantStructureCommitter.Status.RECOVERY_REQUIRED, Optional.empty());

        try {
            for (var pos : attempted) {
                if (!matches(level, pos, targets.get(pos))) throw new IllegalStateException("Natural block changed before notification");
                targets.get(pos).updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
                // 形状更新可能已改变方块，邻居通知必须使用实际状态。
                if (!available(level, pos)) throw new IllegalStateException("Natural chunk became unavailable");
                level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
            }
            for (var entry : checked.expectedStates().entrySet())
                if (!matches(level, entry.getKey(), targets.getOrDefault(entry.getKey(), entry.getValue())))
                    throw new IllegalStateException("Natural group changed after notification");
            if (!metadataMatches(level, plan)) throw new IllegalStateException("Natural registration changed after notification");
        } catch (RuntimeException failure) {
            freeze(level, plan);
            org.slf4j.LoggerFactory.getLogger(RhizomeNaturalPlacement.class).error("Natural rhizome neighbor update failed", failure);
            return new Result(PlantStructureCommitter.Status.RECOVERY_REQUIRED, Optional.empty());
        }
        return new Result(PlantStructureCommitter.Status.COMMITTED, Optional.empty());
    }

    private static Map<BlockPos, BlockState> targets(RhizomeNaturalPreflight.Checked checked) {
        Map<BlockPos, BlockState> all = new HashMap<>();
        checked.plan().plants().values().forEach(plan -> plan.changes().forEach((pos, change) -> all.put(pos, change.replacement())));
        checked.plan().underground().forEach((pos, change) -> all.put(pos, change.replacement()));
        checked.decorationCleanup().keySet().forEach(pos -> all.put(pos, Blocks.AIR.defaultBlockState()));
        Map<BlockPos, BlockState> ordered = new LinkedHashMap<>();
        all.keySet().stream().sorted(java.util.Comparator.comparingInt((BlockPos pos) -> pos.getY())
                .thenComparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getZ()))
                .forEach(pos -> ordered.put(pos, all.get(pos)));
        return ordered;
    }

    private static boolean metadataMatches(ServerLevel level, RhizomeNaturalStructurePlan plan) {
        var individuals = PlantIndividuals.get(level);
        var systems = PlantRhizomeSystems.get(level);
        if (individuals.isReadOnly() || systems.isReadOnly()) return false;
        for (var plant : plan.population().plants().values()) {
            if (!individuals.find(plant.id()).filter(plant::equals).isPresent()) return false;
            Map<BlockPos, String> roles = new HashMap<>();
            plan.plants().get(plant.id()).changes().forEach((pos, change) -> roles.put(pos, change.role()));
            if (!individuals.partsOf(plant.id()).equals(roles)) return false;
        }
        return plan.population().systems().values().stream().allMatch(system ->
                systems.find(system.id()).filter(system::equals).isPresent() && !systems.needsRecovery(system.id()));
    }

    private static void freeze(ServerLevel level, RhizomeNaturalStructurePlan plan) {
        var individuals = PlantIndividuals.get(level);
        var systems = PlantRhizomeSystems.get(level);
        if (!individuals.isReadOnly() && !individuals.isApplyingStructure())
            for (var original : plan.population().plants().values()) individuals.find(original.id()).ifPresent(state -> {
                if (!state.species().equals(original.species()) || !state.base().equals(original.base())) return;
                var reasons = new HashSet<>(state.environmentReasons());
                reasons.add("structure_commit_error");
                individuals.replace(state, state.withEnvironmentReasons(reasons));
            });
        if (!systems.isReadOnly()) for (var system : plan.population().systems().values())
            if (systems.find(system.id()).isPresent()) systems.requireRecovery(system.id());
    }

    private static boolean matches(ServerLevel level, BlockPos pos, BlockState state) {
        try { return available(level, pos) && level.getBlockState(pos).equals(state); }
        catch (RuntimeException failure) { return false; }
    }
    private static boolean available(ServerLevel level, BlockPos pos) {
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.getChunkSource().hasChunk(x, z)
                && level.getChunkSource().isPositionTicking(ChunkPos.asLong(x, z));
    }
}
