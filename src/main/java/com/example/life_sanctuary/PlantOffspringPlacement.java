package com.example.life_sanctuary;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * Loaded-world offspring placement coordinator. Not natural generation.
 * The caller checks species-specific reproduction conditions and constructs the child/plan once.
 */
public final class PlantOffspringPlacement {
    public record Result(PlantSpreadAdmission.Result admission,
            Optional<PlantStructureCommitter.Result> placement) {
        public boolean placed() {
            return placement.filter(result -> result.status() == PlantStructureCommitter.Status.COMMITTED).isPresent();
        }
        public boolean needsRecovery() {
            return placement.filter(result -> result.status() == PlantStructureCommitter.Status.RECOVERY_REQUIRED).isPresent();
        }
    }

    private PlantOffspringPlacement() {}

    /**
     * Server-thread only. Does not consume a parent's reproductive resource or redraw growth targets.
     * Species with finite budgets must use placeWithCost instead; admission alone never spends a budget.
     */
    public static Result place(ServerLevel level, UUID parentId, PlantStructurePlan plan) {
        PlantIndividuals store = PlantIndividuals.get(level);
        if (store.isReadOnly()) return new Result(PlantSpreadAdmission.Result.READ_ONLY, Optional.empty());
        var parent = store.find(parentId).orElse(null);
        if (parent == null) return new Result(PlantSpreadAdmission.Result.INVALID, Optional.empty());
        return place(level, parent, plan);
    }

    /**
     * No-cost entry point: stale plans must not bypass changed pruning, stage or resource state.
     * Capture expectedParent before checking species-specific conditions, on the same server tick.
     */
    public static Result place(ServerLevel level, PlantIndividualState expectedParent, PlantStructurePlan plan) {
        return placeInternal(level, expectedParent, plan, null);
    }

    /** Finite reproduction: debit only as part of the successful child structure commit. */
    public static Result placeWithCost(ServerLevel level, PlantIndividualState expectedParent,
            PlantStructurePlan plan, String budgetKey, int cost) {
        if (budgetKey == null || budgetKey.isBlank() || cost <= 0)
            throw new IllegalArgumentException("Explicit positive reproduction cost required");
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly()) return new Result(PlantSpreadAdmission.Result.READ_ONLY, Optional.empty());
        if (store.find(expectedParent.id()).filter(expectedParent::equals).isEmpty())
            return new Result(PlantSpreadAdmission.Result.STALE_PARENT, Optional.empty());
        if (expectedParent.reproduction().getOrDefault(budgetKey, 0) < cost)
            return new Result(PlantSpreadAdmission.Result.NO_REPRODUCTION_BUDGET, Optional.empty());
        return placeInternal(level, expectedParent, plan,
                new PlantReproductionDebit(expectedParent, budgetKey, cost));
    }

    private static Result placeInternal(ServerLevel level, PlantIndividualState expectedParent,
            PlantStructurePlan plan, PlantReproductionDebit debit) {
        PlantIndividuals store = PlantIndividuals.get(level);
        PlantIndividualState committedParent = debit == null ? expectedParent : debit.after();
        PlantIndividualState child = plan.expectedIndividual();
        // New offspring cannot pretend to own preexisting world structures.
        if (plan.changes().values().stream()
                .anyMatch(change -> change.policy().requiresOwnership()))
            return new Result(PlantSpreadAdmission.Result.INVALID, Optional.empty());

        PlantSpreadAdmission.Result admission = PlantSpreadAdmission.registerOffspring(store, expectedParent, child);
        if (admission != PlantSpreadAdmission.Result.REGISTERED) return new Result(admission, Optional.empty());
        try {
            PlantStructureCommitter.Result placement = PlantStructureCommitter.apply(level, store, plan, child, debit);
            // Neighbor notifications run after the structure lock ends and may change/remove the parent.
            // The world is already committed: keep the child for diagnosis rather than silently retrying.
            if (placement.status() == PlantStructureCommitter.Status.COMMITTED
                    && (store.find(expectedParent.id()).filter(committedParent::equals).isEmpty()
                        || store.find(child.id()).filter(child::equals).isEmpty())) {
                PlantLifecycle.setPauseReason(store, child.id(), "offspring_parent_changed", true);
                placement = new PlantStructureCommitter.Result(
                        PlantStructureCommitter.Status.RECOVERY_REQUIRED, Optional.empty());
            }
            if (placement.status() == PlantStructureCommitter.Status.RECOVERY_REQUIRED)
                PlantLifecycle.setPauseReason(store, expectedParent.id(), "offspring_recovery_required", true);
            if (placement.status() == PlantStructureCommitter.Status.REJECTED
                    || placement.status() == PlantStructureCommitter.Status.ROLLED_BACK) {
                // Removes the timer and derived population count as well as the temporary identity.
                store.remove(child.id(), child.base());
            }
            // Incomplete recovery retains the diagnostic identity and its occupied slot.
            return new Result(admission, Optional.of(placement));
        } catch (RuntimeException exception) {
            PlantLifecycle.setPauseReason(store, expectedParent.id(), "offspring_placement_error", true);
            // A programming error must not silently leave an active, invisible child behind.
            // Preserve the identity for diagnosis if it already owns actual structure positions.
            if (store.partsOf(child.id()).isEmpty()) {
                store.remove(child.id(), child.base());
            } else {
                PlantLifecycle.setPauseReason(store, child.id(), "offspring_placement_error", true);
            }
            throw exception;
        }
    }
}
