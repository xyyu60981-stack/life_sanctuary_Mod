package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** 服务器线程内的出苗事务。跨 SavedData 不保证进程崩溃时的磁盘原子性。 */
public final class RhizomeShootPlacement {
    private RhizomeShootPlacement() {}
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    public record Result(PlantStructureCommitter.Status status, Optional<RhizomeShootPreflight.Result> preflight) {}

    /** 只提交已具体化的提案；参与方块的回调须遵守两种存储的结构锁，不生成掉落等副作用。 */
    public static Result place(ServerLevel level, RhizomeShootStructure.Proposal proposal,
            PlantIndividualState child, Map<RhizomeShootStructure.Kind, String> roles,
            Predicate<BlockState> validRoot) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var checked = RhizomeShootPreflight.check(level, proposal, child, validRoot);
        if (!checked.ready()) return new Result(PlantStructureCommitter.Status.REJECTED, Optional.of(checked));
        var individuals = PlantIndividuals.get(level);
        var systems = PlantRhizomeSystems.get(level);
        var path = proposal.path();
        var nextSystem = checked.proposedSystem().orElseThrow();
        var transfers = RhizomeOverwrite.transfers(systems, path.system(), proposal.parts());
        Map<BlockPos, PlantStructurePlan.Change> childChanges = new LinkedHashMap<>();
        proposal.parts().forEach((pos, part) -> {
            if (part.kind() == RhizomeShootStructure.Kind.RHIZOME
                    || part.kind() == RhizomeShootStructure.Kind.DECORATION_CLEANUP) return;
            String role = roles.get(part.kind());
            if (role == null || role.isBlank()) throw new IllegalArgumentException("Missing child part role");
            var policy = part.before().isAir() ? PlantStructurePlan.Replacement.AIR_ONLY
                    : RhizomePathRules.isAirLike(part.before()) ? PlantStructurePlan.Replacement.DECORATION_ONLY
                    : PlantStructurePlan.Replacement.WATER_PRESERVING;
            childChanges.put(pos, new PlantStructurePlan.Change(part.before(), part.after(), role, policy));
        });
        var childPlan = new PlantStructurePlan(child, childChanges, path.observed());
        // 临时身份只存在于当前服务器调用，不把地下茎登记为幼丛部件。
        individuals.create(child);
        boolean lockedIndividuals = false, lockedSystems = false;
        boolean committed = false, recovered = true;
        ArrayList<BlockPos> attempted = new ArrayList<>();
        try {
            var childCheck = PlantStructurePreflight.check(level, individuals, childPlan);
            if (!childCheck.ready()) {
                individuals.remove(child.id(), child.base());
                return new Result(PlantStructureCommitter.Status.REJECTED, Optional.of(checked));
            }
            systems.beginShootStructure(path.system(), nextSystem, transfers);
            lockedSystems = true;
            individuals.beginStructure(child);
            lockedIndividuals = true;
            for (var entry : proposal.parts().entrySet()) {
                BlockPos pos = entry.getKey();
                var part = entry.getValue();
                if (!available(level, pos) || !level.getBlockState(pos).equals(part.before()))
                    throw new IllegalStateException("Shoot path changed or became unavailable");
                if (part.before().equals(part.after())) continue;
                attempted.add(pos);
                if (!level.setBlock(pos, part.after(), FLAGS) || !level.getBlockState(pos).equals(part.after()))
                    throw new IllegalStateException("Shoot write failed at " + pos);
            }
            if (!matches(level, proposal, true)) throw new IllegalStateException("Shoot placement callback changed path");
            individuals.commitStructure(childPlan, child, null);
            systems.commitShootStructure(path.system(), nextSystem);
            committed = true;
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(RhizomeShootPlacement.class).error("Rhizome shoot placement failed", failure);
            for (int i = attempted.size() - 1; i >= 0; i--) {
                BlockPos pos = attempted.get(i);
                var part = proposal.parts().get(pos);
                try {
                    if (!available(level, pos)) { recovered = false; continue; }
                    var actual = level.getBlockState(pos);
                    if (actual.equals(part.before())) continue;
                    if (!actual.equals(part.after())) { recovered = false; continue; }
                    level.setBlock(pos, part.before(), FLAGS);
                } catch (RuntimeException recoveryFailure) {
                    recovered = false;
                    org.slf4j.LoggerFactory.getLogger(RhizomeShootPlacement.class).error("Cannot restore shoot at " + pos, recoveryFailure);
                }
            }
            recovered &= matches(level, proposal, false);
            if (!recovered && lockedIndividuals && lockedSystems) {
                // 残留方块需要身份及占位用于恢复；保留名额，禁止当作普通失败自动重试。
                try {
                    individuals.commitStructure(childPlan, child, null);
                    systems.commitShootStructure(path.system(), nextSystem);
                } catch (RuntimeException recoveryFailure) {
                    org.slf4j.LoggerFactory.getLogger(RhizomeShootPlacement.class)
                            .error("Cannot retain complete shoot recovery metadata", recoveryFailure);
                }
            }
        } finally {
            if (lockedIndividuals) individuals.endStructure();
            if (lockedSystems) systems.endShootStructure();
        }
        if (!committed) {
            if (recovered) individuals.remove(child.id(), child.base());
            else {
                RhizomeOverwrite.requireRecovery(systems, path.system().id(), transfers);
                pause(individuals, child);
            }
            return result(recovered ? PlantStructureCommitter.Status.ROLLED_BACK : PlantStructureCommitter.Status.RECOVERY_REQUIRED);
        }
        try {
            for (var entry : proposal.parts().entrySet()) {
                if (entry.getValue().before().equals(entry.getValue().after())) continue;
                BlockPos pos = entry.getKey();
                if (!available(level, pos)) throw new IllegalStateException("Shoot chunk became unavailable");
                var current = level.getBlockState(pos);
                current.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
                level.updateNeighborsAt(pos, current.getBlock());
            }
            Map<BlockPos, String> expectedParts = new LinkedHashMap<>();
            childChanges.forEach((pos, change) -> expectedParts.put(pos, change.role()));
            if (!matches(level, proposal, true) || !systems.find(nextSystem.id()).filter(nextSystem::equals).isPresent()
                    || !individuals.find(child.id()).filter(child::equals).isPresent()
                    || transfers.values().stream().anyMatch(transfer -> !systems.find(transfer.after().id()).filter(transfer.after()::equals).isPresent())
                    || !individuals.partsOf(child.id()).equals(expectedParts)) {
                pause(individuals, child);
                RhizomeOverwrite.requireRecovery(systems, path.system().id(), transfers);
                return result(PlantStructureCommitter.Status.RECOVERY_REQUIRED);
            }
        } catch (RuntimeException failure) {
            pause(individuals, child);
            RhizomeOverwrite.requireRecovery(systems, path.system().id(), transfers);
            org.slf4j.LoggerFactory.getLogger(RhizomeShootPlacement.class).error("Shoot neighbor update failed", failure);
            return result(PlantStructureCommitter.Status.RECOVERY_REQUIRED);
        }
        return result(PlantStructureCommitter.Status.COMMITTED);
    }

    private static boolean matches(ServerLevel level, RhizomeShootStructure.Proposal proposal, boolean after) {
        for (var entry : proposal.path().observed().entrySet()) {
            if (!available(level, entry.getKey())) return false;
            var part = proposal.parts().get(entry.getKey());
            var expected = after && part != null ? part.after() : entry.getValue();
            try {
                if (!level.getBlockState(entry.getKey()).equals(expected)) return false;
            } catch (RuntimeException failure) {
                return false;
            }
        }
        return true;
    }

    private static boolean available(ServerLevel level, BlockPos pos) {
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        return !level.isOutsideBuildHeight(pos) && level.getChunkSource().hasChunk(x, z)
                && level.getChunkSource().isPositionTicking(ChunkPos.asLong(x, z));
    }

    private static void pause(PlantIndividuals store, PlantIndividualState child) {
        store.find(child.id()).ifPresent(current -> {
            var reasons = new HashSet<>(current.environmentReasons());
            reasons.add("structure_commit_error");
            store.replace(current, current.withEnvironmentReasons(reasons));
        });
    }

    private static Result result(PlantStructureCommitter.Status status) { return new Result(status, Optional.empty()); }
}
