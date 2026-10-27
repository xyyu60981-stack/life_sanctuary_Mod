package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** 额外成长空间只读取事务快照；尺寸始终XYZ，不能用模型较小而绕过公共空隙要求。 */
public final class PlantGrowthSpace {
    private PlantGrowthSpace() {}
    public enum Status { READY, MISSING_SNAPSHOT, BLOCKED }
    public record Result(Status status, Optional<BlockPos> position) {
        public Result {
            Objects.requireNonNull(status);
            position = position.map(BlockPos::immutable);
            if ((status == Status.READY) != position.isEmpty()) throw new IllegalArgumentException("Invalid growth space result");
        }
        public boolean ready() { return status == Status.READY; }
    }
    private record Transition(ResourceLocation species, String from, String to) {}
    private static final Map<Transition, Rule> RULES = new ConcurrentHashMap<>();

    public record Rule(int sizeX, int sizeY, int sizeZ, BlockPos minimumOffset) {
        public Rule {
            if (sizeX < 1 || sizeY < 1 || sizeZ < 1) throw new IllegalArgumentException("Invalid XYZ growth space");
            Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
            minimumOffset = minimumOffset.immutable();
        }
        public Set<BlockPos> positions(BlockPos base) {
            var positions = new HashSet<BlockPos>();
            for (int x = 0; x < sizeX; x++) for (int y = 0; y < sizeY; y++) for (int z = 0; z < sizeZ; z++)
                positions.add(new BlockPos(Math.addExact(base.getX(), Math.addExact(minimumOffset.getX(), x)),
                        Math.addExact(base.getY(), Math.addExact(minimumOffset.getY(), y)),
                        Math.addExact(base.getZ(), Math.addExact(minimumOffset.getZ(), z))));
            return Set.copyOf(positions);
        }
    }

    public static Rule climbingAdult() { return new Rule(1, 3, 1, new BlockPos(0, 1, 0)); }
    public static Rule cycad(int height) {
        if (height != 1 && height != 2) throw new IllegalArgumentException("Cycad clearance must be XYZ 3x1x3 or 3x2x3");
        return new Rule(3, height, 3, new BlockPos(-1, 0, -1));
    }

    /** 物种定义后在setup注册；只接受定义中真实存在的下一阶段转换，不改其他物种。 */
    public static void register(ResourceLocation species, String stage, Rule rule) {
        var definition = PlantSpeciesRegistry.find(Objects.requireNonNull(species)).orElseThrow();
        var to = definition.requireTransition(stage).nextStage();
        if (RULES.putIfAbsent(new Transition(species, stage, to), Objects.requireNonNull(rule)) != null)
            throw new IllegalArgumentException("Duplicate growth space rule");
    }

    /** 快照采集者必须把这些格子加入observed；本方法不访问世界或加载区块。 */
    public static Set<BlockPos> requiredPositions(ResourceLocation species, String from, String to, BlockPos base) {
        var rule = RULES.get(new Transition(species, from, to));
        return rule == null ? Set.of() : rule.positions(base);
    }

    public static Result check(Rule rule, BlockPos base, Set<BlockPos> owned, Map<BlockPos, BlockState> observed) {
        return inspect(rule, base, pos -> {
            var state = observed.get(pos);
            if (state == null) return Status.MISSING_SNAPSHOT;
            return state.hasBlockEntity() || !state.getFluidState().isEmpty()
                    || !owned.contains(pos) && !state.isAir() && !state.canBeReplaced() ? Status.BLOCKED : Status.READY;
        });
    }

    /** 可复用的只读探针入口；UNKNOWN不能当空气，实际结构提交仍重查完整世界快照。 */
    public static Result inspect(Rule rule, BlockPos base, java.util.function.Function<BlockPos, Status> probe) {
        Objects.requireNonNull(probe);
        for (var pos : rule.positions(base).stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList()) {
            var status = Objects.requireNonNull(probe.apply(pos));
            if (status != Status.READY) return new Result(status, Optional.of(pos));
        }
        return new Result(Status.READY, Optional.empty());
    }

    static Result check(PlantIndividualState before, String targetStage, Set<BlockPos> owned,
            Map<BlockPos, BlockState> observed) {
        var rule = RULES.get(new Transition(before.species(), before.stage(), targetStage));
        return rule == null ? new Result(Status.READY, Optional.empty()) : check(rule, before.base(), owned, observed);
    }

    static Result check(PlantStructurePlan plan, PlantIndividualState next, Set<BlockPos> owned) {
        if (plan.expectedIndividual().stage().equals(next.stage())) return new Result(Status.READY, Optional.empty());
        var observed = new HashMap<>(plan.dependencies());
        plan.changes().forEach((pos, change) -> observed.put(pos, change.expected()));
        return check(plan.expectedIndividual(), next.stage(), owned, observed);
    }
}
