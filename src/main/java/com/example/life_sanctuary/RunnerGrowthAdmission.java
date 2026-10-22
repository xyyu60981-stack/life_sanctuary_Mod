package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Pure single-step geometry/budget planning. No world reads, random rolls, timers or resource writes. */
public final class RunnerGrowthAdmission {
    private RunnerGrowthAdmission() {}

    public enum Action { EXTEND, BRANCH }
    public enum Reason { READY, SOURCE_DISABLED, PAUSED, INVALID_POSITION, INVALID_ATTACHMENT,
        LENGTH_LIMIT, NO_BRANCH_CHANCE, INVALID_TOPOLOGY, MISSING_BUDGET, STALE_BUDGET }

    /** Runner allowances are separate from reproductive and harvest budgets; they have different reset rules. */
    public record Budget(int baseLength, int extraLength, int branchChances) {
        public Budget {
            if (baseLength <= 0 || extraLength < 0 || branchChances < 0
                    || (long) baseLength + extraLength > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid runner budget");
        }
        public int maximumLength() { return baseLength + extraLength; }
    }

    public record Proposal(PlantRunnerNetwork network, Budget budget, BlockPos added, Action action) {
        public Proposal { added = added.immutable(); }
    }
    public record Result(Reason reason, Optional<Proposal> proposal) {
        public boolean ready() { return reason == Reason.READY; }
    }

    /**
     * The adapter chooses an endpoint/branch site using its own direction, peat and random rules.
     * BRANCH adds one side segment to a straight middle segment; an L turn is not a branch.
     * Apply the returned budget only after successful world/state commit, never during candidate search.
     */
    public static Result plan(PlantIndividualState individual, PlantRunnerNetwork network, Budget budget,
            BlockPos attachment, BlockPos candidate, Action action) {
        java.util.Objects.requireNonNull(action);
        if (individual.runnerBudget().isEmpty()) return rejected(Reason.MISSING_BUDGET);
        if (!individual.runnerBudget().get().equals(budget)) return rejected(Reason.STALE_BUDGET);
        if (!individual.source().canExpand()) return rejected(Reason.SOURCE_DISABLED);
        if (individual.pruned() || !individual.environmentReasons().isEmpty()) return rejected(Reason.PAUSED);
        if (!network.positions().contains(attachment) || network.positions().contains(candidate)
                || candidate.getY() != attachment.getY()
                || Math.abs((long) candidate.getX() - attachment.getX())
                    + Math.abs((long) candidate.getZ() - attachment.getZ()) != 1)
            return rejected(Reason.INVALID_POSITION);

        boolean north = network.positions().contains(attachment.relative(Direction.NORTH));
        boolean south = network.positions().contains(attachment.relative(Direction.SOUTH));
        boolean west = network.positions().contains(attachment.relative(Direction.WEST));
        boolean east = network.positions().contains(attachment.relative(Direction.EAST));
        int degree = (north ? 1 : 0) + (south ? 1 : 0) + (west ? 1 : 0) + (east ? 1 : 0);
        Budget nextBudget = budget;
        if (action == Action.EXTEND) {
            if (degree > 1) return rejected(Reason.INVALID_ATTACHMENT);
            if (network.positions().size() >= budget.maximumLength()) return rejected(Reason.LENGTH_LIMIT);
        } else {
            if (network.maximumDegree() < 3 || degree != 2 || !((north && south) || (west && east)))
                return rejected(Reason.INVALID_ATTACHMENT);
            if (budget.branchChances() == 0) return rejected(Reason.NO_BRANCH_CHANCE);
            // A successful branch grants exactly its own added segment, never another free extension.
            // Existing over-limit structures remain untouched and cannot escape the limit by branching.
            if (network.positions().size() > budget.maximumLength()
                    || budget.maximumLength() == Integer.MAX_VALUE) return rejected(Reason.LENGTH_LIMIT);
            nextBudget = new Budget(budget.baseLength(), budget.extraLength() + 1, budget.branchChances() - 1);
        }
        var expanded = network.add(candidate);
        if (expanded.isEmpty()) return rejected(Reason.INVALID_TOPOLOGY);
        return new Result(Reason.READY, Optional.of(new Proposal(expanded.get(), nextBudget, candidate, action)));
    }

    private static Result rejected(Reason reason) { return new Result(reason, Optional.empty()); }
}
