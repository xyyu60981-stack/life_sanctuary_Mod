package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * Server-thread structure application. Neighbor shape updates are deferred until all parts exist.
 * Rollback restores only our own writes, never a different block introduced by another callback.
 * External side effects (items, sounds, entities) cannot be rolled back: participating blocks must
 * keep placement/removal callbacks free of such effects during isApplyingStructure(owner).
 */
public final class PlantStructureCommitter {
    public enum Status { COMMITTED, REJECTED, ROLLED_BACK, RECOVERY_REQUIRED }
    public record Result(Status status, Optional<PlantStructurePreflight.Result> preflight) {}
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private PlantStructureCommitter() {}

    public static Result apply(ServerLevel level, PlantIndividuals store, PlantStructurePlan plan,
            PlantIndividualState next) {
        return apply(level, store, plan, next, null);
    }

    /** Package-only offspring transaction; ordinary structural edits cannot debit another plant. */
    static Result apply(ServerLevel level, PlantIndividuals store, PlantStructurePlan plan,
            PlantIndividualState next, PlantReproductionDebit debit) {
        return apply(level, store, plan, next, debit, false);
    }

    /**
     * One live runner adds exactly one segment. The adapter supplies the complete old runner read set
     * and any connection-shape changes; species-specific substrate and direction rules remain external.
     * Budget changes use the same locked commit as the new structural ownership, never an earlier write.
     */
    public static Result applyRunnerGrowth(ServerLevel level, PlantStructurePlan plan,
            PlantRunnerNetwork network, BlockPos attachment, BlockPos candidate,
            RunnerGrowthAdmission.Action action, String runnerRole) {
        if (runnerRole == null || runnerRole.isBlank()) throw new IllegalArgumentException("Missing runner role");
        var store = PlantIndividuals.get(level);
        var before = plan.expectedIndividual();
        if (store.isReadOnly() || before.runnerBudget().isEmpty()
                || !store.find(before.id()).filter(before::equals).isPresent())
            return new Result(Status.REJECTED, Optional.empty());
        var owned = store.partsOf(before.id());
        var runnerPositions = new HashSet<BlockPos>();
        owned.forEach((pos, role) -> { if (runnerRole.equals(role)) runnerPositions.add(pos); });
        if (!runnerPositions.equals(network.positions())) return new Result(Status.REJECTED, Optional.empty());
        var growth = RunnerGrowthAdmission.plan(before, network, before.runnerBudget().get(),
                attachment, candidate, action);
        if (!growth.ready()) return new Result(Status.REJECTED, Optional.empty());
        var added = plan.changes().get(candidate);
        if (added == null || !runnerRole.equals(added.role()) || owned.containsKey(candidate)
                || added.policy().requiresOwnership())
            throw new IllegalArgumentException("Runner growth must add its declared segment");
        for (var entry : plan.changes().entrySet()) {
            if (entry.getKey().equals(candidate)) continue;
            // Only existing runner connection states may accompany this one-segment extension.
            if (!network.positions().contains(entry.getKey()) || !runnerRole.equals(entry.getValue().role())
                    || !entry.getValue().policy().requiresOwnership())
                throw new IllegalArgumentException("Runner growth cannot change unrelated structures");
        }
        for (BlockPos pos : network.positions()) {
            var change = plan.changes().get(pos);
            var expected = change == null ? plan.dependencies().get(pos) : change.expected();
            if (expected == null || expected.isAir())
                throw new IllegalArgumentException("Every original runner segment needs a non-air read dependency");
        }
        var next = before.withRunnerBudget(growth.proposal().orElseThrow().budget());
        Result result = apply(level, store, plan, next, null, true);
        if (result.status() == Status.COMMITTED) {
            var expectedParts = new java.util.HashMap<>(owned);
            expectedParts.put(candidate.immutable(), runnerRole);
            if (!store.find(next.id()).filter(next::equals).isPresent()
                    || !store.partsOf(next.id()).equals(expectedParts)) {
                pauseForRecovery(store, next.id());
                return new Result(Status.RECOVERY_REQUIRED, Optional.empty());
            }
        }
        return result;
    }

    private static Result apply(ServerLevel level, PlantIndividuals store, PlantStructurePlan plan,
            PlantIndividualState next, PlantReproductionDebit debit, boolean runnerGrowth) {
        if (store != PlantIndividuals.get(level)) throw new IllegalArgumentException("Wrong dimension store");
        if (debit != null && (debit.expected().id().equals(next.id())
                || !store.find(debit.expected().id()).filter(debit.expected()::equals).isPresent()))
            return new Result(Status.REJECTED, Optional.empty());
        var before = plan.expectedIndividual();
        // World construction is not a way to refresh resources, change source or move an individual.
        if (!before.id().equals(next.id()) || !before.species().equals(next.species())
                || !before.base().equals(next.base()) || before.source() != next.source()
                || before.origin() != next.origin()
                || !before.population().equals(next.population()) || !before.harvests().equals(next.harvests())
                || !before.reproduction().equals(next.reproduction())
                || (!runnerGrowth && !before.runnerBudget().equals(next.runnerBudget()))
                || before.canBePruned() != next.canBePruned() || before.pruned() != next.pruned()
                || !before.environmentReasons().equals(next.environmentReasons()))
            throw new IllegalArgumentException("Structure commit cannot change identity or resource permissions");
        validateStage(before, next);
        var checked = PlantStructurePreflight.check(level, store, plan);
        if (!checked.ready()) return new Result(Status.REJECTED, Optional.of(checked));

        ArrayList<BlockPos> attempted = new ArrayList<>();
        boolean committed = false;
        boolean recovered = true;
        store.beginStructure(before);
        try {
            for (var entry : plan.changes().entrySet()) {
                BlockPos pos = entry.getKey();
                var change = entry.getValue();
                if (!level.getBlockState(pos).equals(change.expected()))
                    throw new IllegalStateException("World changed during structure placement at " + pos);
                if (change.expected().equals(change.replacement())) continue;
                // Record before writing: setBlock may change state and then throw from a callback.
                attempted.add(pos);
                if (!level.setBlock(pos, change.replacement(), FLAGS)
                        || !level.getBlockState(pos).equals(change.replacement()))
                    throw new IllegalStateException("Structure write failed at " + pos);
            }
            for (var entry : plan.changes().entrySet())
                if (!level.getBlockState(entry.getKey()).equals(entry.getValue().replacement()))
                    throw new IllegalStateException("Structure was changed by a placement callback");
            for (var entry : plan.dependencies().entrySet())
                if (!plan.changes().containsKey(entry.getKey())
                        && !level.getBlockState(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException("Structure support changed during placement");

            store.commitStructure(plan, next, debit);
            committed = true;
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(PlantStructureCommitter.class)
                    .error("Structure placement failed for " + before.id(), exception);
            for (int i = attempted.size() - 1; i >= 0; i--) {
                BlockPos pos = attempted.get(i);
                var change = plan.changes().get(pos);
                try {
                    var actual = level.getBlockState(pos);
                    if (actual.equals(change.expected())) continue;
                    if (!actual.equals(change.replacement())) {
                        recovered = false;
                        continue;
                    }
                    level.setBlock(pos, change.expected(), FLAGS);
                    recovered &= level.getBlockState(pos).equals(change.expected());
                } catch (RuntimeException recoveryFailure) {
                    recovered = false;
                    org.slf4j.LoggerFactory.getLogger(PlantStructureCommitter.class)
                            .error("Cannot restore plant structure at " + pos, recoveryFailure);
                }
            }
        } finally {
            store.endStructure();
        }
        if (!committed) {
            // Also verify no callback changed an untouched target or dependency.
            for (var entry : plan.changes().entrySet())
                recovered &= level.getBlockState(entry.getKey()).equals(entry.getValue().expected());
            for (var entry : plan.dependencies().entrySet())
                recovered &= level.getBlockState(entry.getKey()).equals(entry.getValue());
            // Any callback failure pauses this individual; no repeated half-successful side effects.
            pauseForRecovery(store, before.id());
            return new Result(recovered ? Status.ROLLED_BACK : Status.RECOVERY_REQUIRED, Optional.empty());
        }

        try {
            // Notify once per actual block-state change, after the complete structure owns its parts.
            // Compare blocks, not fluids: water -> waterlogged plant must still notify observers.
            // updateNeighbourShapes reaches ObserverBlock.updateShape; no custom pulse is needed.
            for (var entry : plan.changes().entrySet()) {
                if (entry.getValue().expected().equals(entry.getValue().replacement())) continue;
                BlockPos pos = entry.getKey();
                var current = level.getBlockState(pos);
                current.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
                level.updateNeighborsAt(pos, current.getBlock());
            }
            for (var entry : plan.changes().entrySet()) {
                if (!level.getBlockState(entry.getKey()).equals(entry.getValue().replacement())) {
                    pauseForRecovery(store, before.id());
                    return new Result(Status.RECOVERY_REQUIRED, Optional.empty());
                }
            }
            for (var entry : plan.dependencies().entrySet()) {
                if (!plan.changes().containsKey(entry.getKey())
                        && !level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                    pauseForRecovery(store, before.id());
                    return new Result(Status.RECOVERY_REQUIRED, Optional.empty());
                }
            }
        } catch (RuntimeException exception) {
            pauseForRecovery(store, before.id());
            org.slf4j.LoggerFactory.getLogger(PlantStructureCommitter.class)
                    .error("Post-placement neighbor update failed for " + before.id(), exception);
            return new Result(Status.RECOVERY_REQUIRED, Optional.empty());
        }
        return new Result(Status.COMMITTED, Optional.empty());
    }

    private static void pauseForRecovery(PlantIndividuals store, java.util.UUID id) {
        store.find(id).ifPresent(state -> {
            var reasons = new HashSet<>(state.environmentReasons());
            reasons.add("structure_commit_error");
            store.replace(state, state.withEnvironmentReasons(reasons));
        });
    }

    private static void validateStage(PlantIndividualState before, PlantIndividualState next) {
        if (before.stage().equals(next.stage())) {
            if (before.targetTicks() != next.targetTicks() || before.elapsedTicks() != next.elapsedTicks())
                throw new IllegalArgumentException("Structural edits cannot reset a stage timer");
            return;
        }
        if (!PlantLifecycle.canRunTimer(before) || before.remainingTicks() != 0 || next.elapsedTicks() != 0)
            throw new IllegalArgumentException("Stage is not ready");
        var definition = PlantSpeciesRegistry.find(before.species()).orElseThrow();
        var transition = definition.requireTransition(before.stage());
        if (!transition.nextStage().equals(next.stage()))
            throw new IllegalArgumentException("Undeclared stage transition");
        var nextTransition = definition.stages().get(next.stage()).transition();
        if (nextTransition.isEmpty()) {
            if (next.targetTicks() != 0) throw new IllegalArgumentException("Terminal stage has a growth timer");
        } else if (next.targetTicks() < nextTransition.get().minimumTicks()
                || next.targetTicks() > nextTransition.get().maximumTicks()) {
            throw new IllegalArgumentException("Next stage timer outside species definition");
        }
    }
}
