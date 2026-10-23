package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

/** 地下茎单步候选：直行合法时直行，遇固定区域边界或阻挡时尝试两侧转向。 */
public final class RhizomeGrowthSelection {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private RhizomeGrowthSelection() {}

    public record Choice(BlockPos attachment, BlockPos target, Direction direction, PlantRhizomeSystem system) {
        public Choice { attachment = attachment.immutable(); target = target.immutable(); }
    }

    /**
     * legalTarget 须先确认区块可用，再检查统一路径白名单、占位和物种环境条件；不能强制加载。
     * 本方法不选择阶段延伸长度、不补生幼丛、不写世界，也不刷新 2～3 个共享名额。
     */
    public static Optional<Choice> select(PlantRhizomeSystem system, BlockPos endpoint, Direction preferred,
            Predicate<BlockPos> legalTarget, RandomSource random) {
        if (system.growth().isEmpty()) return Optional.empty();
        var arms = system.growth().get().allTips().entrySet().stream().filter(entry ->
                entry.getValue().position().equals(endpoint) && entry.getValue().direction() == preferred).toList();
        return arms.size() == 1 ? select(system, arms.getFirst().getKey(), legalTarget, random) : Optional.empty();
    }

    /** 优先使用稳定端点键读取已保存方向和剩余长度，不由调用方重新选方向或领取长度。 */
    public static Optional<Choice> select(PlantRhizomeSystem system, String arm,
            Predicate<BlockPos> legalTarget, RandomSource random) {
        java.util.Objects.requireNonNull(legalTarget);
        java.util.Objects.requireNonNull(random);
        if (!system.source().canExpand() || system.growth().isEmpty()) return Optional.empty();
        var tip = system.growth().get().tip(arm);
        if (tip == null || tip.remaining() == 0) return Optional.empty();
        BlockPos endpoint = tip.position();
        Direction preferred = tip.direction();
        if (!system.rhizomes().contains(endpoint) && !system.isFiberAnchor(endpoint)) return Optional.empty();
        // 方向与生长端由物种保存；禁止为了绕过边界向后折返或上下爬升。
        var straight = candidate(system, arm, endpoint, preferred, legalTarget);
        if (straight.isPresent()) return straight;
        var turns = new ArrayList<Direction>(2);
        for (Direction direction : HORIZONTAL) {
            if (direction.getAxis() == preferred.getAxis()) continue;
            if (legalCandidate(system, endpoint.relative(direction), legalTarget)) turns.add(direction);
        }
        if (turns.isEmpty()) return Optional.empty();
        // 只有模型与环境都通过的侧路参与抽选；不能抽中无效根位后漏掉另一条合法侧路。
        Direction chosen = turns.size() == 1 ? turns.getFirst() : turns.get(random.nextInt(turns.size()));
        // 仅为选定方向复制节点集合；不为两个候选各构建一份完整系统。
        return system.extendTip(arm, chosen).map(next -> new Choice(endpoint, endpoint.relative(chosen), chosen, next));
    }

    private static Optional<Choice> candidate(PlantRhizomeSystem system, String arm, BlockPos endpoint, Direction direction,
            Predicate<BlockPos> legalTarget) {
        BlockPos target = endpoint.relative(direction);
        if (!legalCandidate(system, target, legalTarget)) return Optional.empty();
        return system.extendTip(arm, direction).map(next -> new Choice(endpoint, target, direction, next));
    }

    private static boolean legalCandidate(PlantRhizomeSystem system, BlockPos target, Predicate<BlockPos> legalTarget) {
        // 先排除本系统的全部地下节点和纤维根，避免无效候选触发世界读取或物种回调。
        return system.canExtendInto(target) && legalTarget.test(target);
    }
}
