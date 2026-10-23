package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

/**
 * 按需运行的末端选择器。只考虑相邻四格，不扩大寻路范围，不写世界或消耗额度。
 * 物种适配器提供空间、基底、水分等合法性检查，并明确哪些末端属于主走茎。
 */
public final class RunnerGrowthSelection {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private RunnerGrowthSelection() {}

    /** 仅在初次种植或真正拆分重抽时调用；重载、普通修剪和失败重试不能调用。 */
    public static RunnerGrowthAdmission.Budget initialBudget(int minimumLength, int maximumLength,
            boolean branchingSpecies, RandomSource random) {
        Objects.requireNonNull(random);
        if (minimumLength <= 0 || maximumLength < minimumLength)
            throw new IllegalArgumentException("Invalid initial runner length range");
        int length = minimumLength == maximumLength ? minimumLength
                : minimumLength + random.nextInt(maximumLength - minimumLength + 1);
        int chances = branchingSpecies && random.nextInt(5) < 2 ? 1 : 0;
        return new RunnerGrowthAdmission.Budget(length, 0, chances);
    }

    public record Choice(BlockPos attachment, Direction direction, boolean peat,
            RunnerGrowthAdmission.Proposal proposal) {
        public Choice { attachment = attachment.immutable(); }
    }

    private record Candidate(BlockPos attachment, BlockPos target, Direction direction, boolean peat) {}
    private record End(BlockPos position, List<Candidate> choices, boolean hasPeat) {}

    /**
     * 一次到期生长只投一次分叉概率。currentEndpoint 由适配器提供，必须是当前末端；
     * 只有明确属于主茎的末端才尝试在其紧邻的直线中段分叉。侧枝不能自行变成主茎。
     * 未命中或没有合法侧位时，回到通常的末端选择，不再次投分叉概率。
     */
    public static Optional<Choice> selectGrowth(PlantIndividualState individual, PlantRunnerNetwork network,
            BlockPos currentEndpoint, Map<BlockPos, Direction> mainDirections, Predicate<BlockPos> legalTarget,
            Predicate<BlockPos> peatSubstrate, boolean allowPeatRewards, RandomSource random) {
        Objects.requireNonNull(legalTarget);
        Objects.requireNonNull(peatSubstrate);
        Objects.requireNonNull(random);
        var endpoints = network.endpoints();
        if (!endpoints.contains(currentEndpoint))
            throw new IllegalArgumentException("Current growth position must be a runner endpoint");
        validateMainDirections(endpoints, mainDirections);
        // 分叉失败后可能与延伸检查同一格；缓存仅存活于本次调用，不保留过期环境结果。
        Map<BlockPos, Boolean> legal = new java.util.HashMap<>();
        Map<BlockPos, Boolean> peat = new java.util.HashMap<>();
        Predicate<BlockPos> cachedLegal = pos -> legal.computeIfAbsent(pos.immutable(), legalTarget::test);
        Predicate<BlockPos> cachedPeat = pos -> peat.computeIfAbsent(pos.immutable(), peatSubstrate::test);
        var budget = individual.runnerBudget();
        if (budget.isPresent() && individual.source().canExpand() && !individual.pruned()
                && individual.environmentReasons().isEmpty() && network.maximumDegree() == 3
                && budget.get().branchChances() > 0 && mainDirections.containsKey(currentEndpoint)
                && network.positions().size() <= budget.get().maximumLength()
                && budget.get().maximumLength() < Integer.MAX_VALUE && random.nextBoolean()) {
            var branch = selectBranch(individual, network, currentEndpoint, cachedLegal, cachedPeat,
                    allowPeatRewards, random);
            if (branch.isPresent()) return branch;
        }
        return selectExtension(individual, network, mainDirections, cachedLegal, cachedPeat, allowPeatRewards, random);
    }

    private static Optional<Choice> selectBranch(PlantIndividualState individual, PlantRunnerNetwork network,
            BlockPos endpoint, Predicate<BlockPos> legalTarget, Predicate<BlockPos> peatSubstrate,
            boolean allowPeatRewards, RandomSource random) {
        BlockPos middle = null;
        Direction axis = null;
        for (Direction direction : HORIZONTAL) {
            if (network.positions().contains(endpoint.relative(direction))) {
                middle = endpoint.relative(direction);
                axis = direction;
                break;
            }
        }
        if (middle == null) return Optional.empty();
        // 中段的两个邻居必须相对，不能把 L 形拐角或已有分叉当作新的分叉点。
        int degree = 0;
        for (Direction side : HORIZONTAL)
            if (network.positions().contains(middle.relative(side))) degree++;
        if (degree != 2 || !network.positions().contains(middle.relative(axis))) return Optional.empty();

        List<Candidate> choices = new ArrayList<>(2);
        for (Direction side : HORIZONTAL) {
            if (side.getAxis() == axis.getAxis()) continue;
            BlockPos candidate = middle.relative(side);
            if (network.positions().contains(candidate)) continue;
            int neighbors = 0;
            for (Direction neighbor : HORIZONTAL)
                if (network.positions().contains(candidate.relative(neighbor))) neighbors++;
            if (neighbors != 1 || !legalTarget.test(candidate)) continue;
            choices.add(new Candidate(middle, candidate, side, peatSubstrate.test(candidate.below())));
        }
        if (choices.isEmpty()) return Optional.empty();
        boolean preferPeat = choices.stream().anyMatch(Candidate::peat);
        Candidate selected = choose(choices.stream().filter(choice -> !preferPeat || choice.peat()).toList(), random);
        var admitted = RunnerGrowthAdmission.plan(individual, network, individual.runnerBudget().orElseThrow(),
                selected.attachment(), selected.target(), RunnerGrowthAdmission.Action.BRANCH,
                allowPeatRewards && selected.peat());
        return admitted.proposal().map(proposal -> new Choice(selected.attachment(), selected.direction(), selected.peat(), proposal));
    }

    /**
     * legalTarget 必须先检查区块已加载且可用，再读取环境；禁止为了搜索强制加载区块。
     * peatSubstrate 接收候选正下方坐标，只在候选合法且通过额度/拓扑准入后调用。
     * 主末端传入原方向；侧枝不传方向，即在可用方向中随机。原方向不合法时允许转向。
     * 泥炭优先影响选点，allowPeatRewards 只控制是否发放物种专属奖励。
     */
    public static Optional<Choice> selectExtension(PlantIndividualState individual, PlantRunnerNetwork network,
            Map<BlockPos, Direction> mainDirections, Predicate<BlockPos> legalTarget,
            Predicate<BlockPos> peatSubstrate, boolean allowPeatRewards, RandomSource random) {
        Objects.requireNonNull(legalTarget);
        Objects.requireNonNull(peatSubstrate);
        Objects.requireNonNull(random);
        var endpoints = network.endpoints();
        validateMainDirections(endpoints, mainDirections);
        if (individual.runnerBudget().isEmpty() || !individual.source().canExpand()
                || individual.pruned() || !individual.environmentReasons().isEmpty()) return Optional.empty();
        var budget = individual.runnerBudget().orElseThrow();
        if (network.positions().size() >= budget.maximumLength()) return Optional.empty();

        List<End> ends = new ArrayList<>();
        // 固定枚举顺序，避免相同种子因 HashSet 顺序变化而选择不同结果。
        for (BlockPos endpoint : endpoints.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList()) {
            if (budget.usedStarts().containsKey(endpoint)) continue;
            List<Candidate> choices = new ArrayList<>(3);
            for (Direction direction : HORIZONTAL) {
                BlockPos candidate = endpoint.relative(direction);
                if (network.positions().contains(candidate)) continue;
                // 只连接本末端才不会产生环或跨接；局部四次查询代替逐候选重建整张图。
                int neighbors = 0;
                for (Direction side : HORIZONTAL)
                    if (network.positions().contains(candidate.relative(side))) neighbors++;
                if (neighbors != 1 || !legalTarget.test(candidate)) continue;
                boolean peat = peatSubstrate.test(candidate.below());
                choices.add(new Candidate(endpoint, candidate, direction, peat));
            }
            if (!choices.isEmpty())
                ends.add(new End(endpoint, List.copyOf(choices), choices.stream().anyMatch(Candidate::peat)));
        }
        if (ends.isEmpty()) return Optional.empty();

        boolean preferPeat = ends.stream().anyMatch(End::hasPeat);
        var eligibleEnds = ends.stream().filter(end -> !preferPeat || end.hasPeat()).toList();
        // 先等概率选末端，不能因某端有更多泥炭落点而提高其权重。
        End selected = choose(eligibleEnds, random);
        var candidates = selected.choices().stream().filter(choice -> !preferPeat || choice.peat()).toList();
        Direction preferred = mainDirections.get(selected.position());
        if (preferred != null) {
            var straight = candidates.stream().filter(choice -> choice.direction() == preferred).findFirst();
            if (straight.isPresent()) return Optional.of(buildChoice(straight.get(), individual, network, allowPeatRewards));
        }
        return Optional.of(buildChoice(choose(candidates, random), individual, network, allowPeatRewards));
    }

    private static Choice buildChoice(Candidate selected, PlantIndividualState individual,
            PlantRunnerNetwork network, boolean allowPeatRewards) {
        // 只为最终落点构建一次图，并由统一准入再次验证额度和拓扑。
        var proposal = RunnerGrowthAdmission.plan(individual, network, individual.runnerBudget().orElseThrow(),
                selected.attachment(), selected.target(), RunnerGrowthAdmission.Action.EXTEND,
                allowPeatRewards && selected.peat())
                .proposal().orElseThrow();
        return new Choice(selected.attachment(), selected.direction(), selected.peat(), proposal);
    }

    private static <T> T choose(List<T> values, RandomSource random) {
        return values.size() == 1 ? values.getFirst() : values.get(random.nextInt(values.size()));
    }

    private static void validateMainDirections(java.util.Set<BlockPos> endpoints, Map<BlockPos, Direction> mainDirections) {
        mainDirections.forEach((pos, direction) -> {
            if (!endpoints.contains(pos) || direction.getAxis() == Direction.Axis.Y)
                throw new IllegalArgumentException("Main direction must belong to a horizontal endpoint");
        });
    }
}
