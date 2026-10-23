package com.example.life_sanctuary;

import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Pure single-step geometry/budget planning. No world reads, random rolls, timers or resource writes. */
public final class RunnerGrowthAdmission {
    private RunnerGrowthAdmission() {}

    public enum Action { EXTEND, BRANCH }
    public enum Reason { READY, SOURCE_DISABLED, PAUSED, INVALID_POSITION, INVALID_ATTACHMENT,
        LENGTH_LIMIT, NO_BRANCH_CHANCE, INVALID_TOPOLOGY, MISSING_BUDGET, STALE_BUDGET, START_ALREADY_USED }

    /** Runner allowances are separate from reproductive and harvest budgets; they have different reset rules. */
    public record Budget(int baseLength, int extraLength, int branchChances, int peatRewardsUsed,
            Map<BlockPos, Direction> usedStarts) {
        public Budget(int baseLength, int extraLength, int branchChances) {
            this(baseLength, extraLength, branchChances, 0);
        }
        public Budget(int baseLength, int extraLength, int branchChances, int peatRewardsUsed) {
            this(baseLength, extraLength, branchChances, peatRewardsUsed, Map.of());
        }
        public Budget {
            Map<BlockPos, Direction> copied = new HashMap<>();
            usedStarts.forEach((pos, direction) -> {
                if (direction.getAxis() == Direction.Axis.Y)
                    throw new IllegalArgumentException("Runner start direction must be horizontal");
                copied.put(pos.immutable(), direction);
            });
            usedStarts = Map.copyOf(copied);
            if (baseLength <= 0 || extraLength < 0 || branchChances < 0
                    || peatRewardsUsed < 0 || peatRewardsUsed > 2
                    || (long) baseLength + extraLength > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid runner budget");
        }
        public int maximumLength() { return baseLength + extraLength; }
        public Budget withStarts(Map<BlockPos, Direction> starts) {
            return new Budget(baseLength, extraLength, branchChances, peatRewardsUsed, starts);
        }
        /** 切掉起点或其首次长出的相邻段后，才解除该起点的占用记忆。 */
        public Map<BlockPos, Direction> survivingStarts(java.util.Set<BlockPos> remaining) {
            Map<BlockPos, Direction> result = new HashMap<>();
            usedStarts.forEach((pos, direction) -> {
                if (remaining.contains(pos) && remaining.contains(pos.relative(direction))) result.put(pos, direction);
            });
            return Map.copyOf(result);
        }
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
        return plan(individual, network, budget, attachment, candidate, action, false);
    }

    /** 仅在新段实际落于泥炭且物种允许时传 true；候选规划不修改存档。 */
    public static Result plan(PlantIndividualState individual, PlantRunnerNetwork network, Budget budget,
            BlockPos attachment, BlockPos candidate, Action action, boolean eligiblePeat) {
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
            if (budget.usedStarts().containsKey(attachment)) return rejected(Reason.START_ALREADY_USED);
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
            nextBudget = new Budget(budget.baseLength(), budget.extraLength() + 1,
                    budget.branchChances() - 1, budget.peatRewardsUsed(), budget.usedStarts());
        }
        var expanded = network.add(candidate);
        if (expanded.isEmpty()) return rejected(Reason.INVALID_TOPOLOGY);
        // 分叉先消耗旧额度，成功落地后才奖励；不能借本次奖励突破准入条件。
        if (eligiblePeat && nextBudget.peatRewardsUsed() < 2 && nextBudget.branchChances() < Integer.MAX_VALUE)
            nextBudget = new Budget(nextBudget.baseLength(), nextBudget.extraLength(),
                    nextBudget.branchChances() + 1, nextBudget.peatRewardsUsed() + 1, nextBudget.usedStarts());
        // 首次离开起点才记录方向，失败候选不占用；合并可保留多个原始起点。
        if (action == Action.EXTEND && (network.positions().size() == 1 || attachment.equals(individual.base()))) {
            Map<BlockPos, Direction> starts = new HashMap<>(nextBudget.usedStarts());
            Direction direction = candidate.getX() > attachment.getX() ? Direction.EAST
                    : candidate.getX() < attachment.getX() ? Direction.WEST
                    : candidate.getZ() > attachment.getZ() ? Direction.SOUTH : Direction.NORTH;
            starts.put(attachment.immutable(), direction);
            nextBudget = new Budget(nextBudget.baseLength(), nextBudget.extraLength(),
                    nextBudget.branchChances(), nextBudget.peatRewardsUsed(), starts);
        }
        return new Result(Reason.READY, Optional.of(new Proposal(expanded.get(), nextBudget, candidate, action)));
    }

    private static Result rejected(Reason reason) { return new Result(reason, Optional.empty()); }
}
