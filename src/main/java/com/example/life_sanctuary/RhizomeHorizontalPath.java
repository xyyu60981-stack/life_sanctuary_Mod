package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** 末端剩余长度的完整横向路径规划，只作有限候选读取，不修改世界或持久化预算。 */
public final class RhizomeHorizontalPath {
    private RhizomeHorizontalPath() {}
    public enum Reason {
        READY, READ_ONLY, RECOVERY_REQUIRED, STALE_SYSTEM, SOURCE_DISABLED, NO_BUDGET,
        ATTACHMENT_UNAVAILABLE, INVALID_ATTACHMENT, PATH_BLOCKED, WORLD_CHANGED, APPLYING_STRUCTURE
    }
    public record Result(Reason reason, Optional<Plan> plan) {
        public boolean ready() { return reason == Reason.READY; }
    }

    public record Plan(PlantRhizomeSystem expected, String arm, List<Direction> directions,
            Map<BlockPos, BlockState> replacements, Map<BlockPos, BlockState> dependencies) {
        public Plan {
            Objects.requireNonNull(expected);
            directions = List.copyOf(directions);
            replacements = immutable(replacements);
            dependencies = immutable(dependencies);
            var tip = expected.growth().orElseThrow().tip(arm);
            if (tip == null || directions.isEmpty() || directions.size() != tip.remaining()
                    || !dependencies.containsKey(tip.position()))
                throw new IllegalArgumentException("Plan must cover the complete saved tip budget");
            var replay = expected;
            var targets = new java.util.HashSet<BlockPos>();
            for (Direction direction : directions) {
                replay = replay.extendTip(arm, direction).orElseThrow(() -> new IllegalArgumentException("Invalid horizontal path"));
                BlockPos pos = replay.growth().orElseThrow().tip(arm).position();
                BlockState before = Objects.requireNonNull(dependencies.get(pos), "Missing target snapshot");
                BlockState after = Objects.requireNonNull(replacements.get(pos), "Missing target replacement");
                if (!RhizomePathRules.canConvert(before) && !(before.getBlock() instanceof RhizomeBlock))
                    throw new IllegalArgumentException("Invalid horizontal conversion");
                new RhizomeShootStructure.Part(RhizomeShootStructure.Kind.RHIZOME, before, after);
                targets.add(pos);
            }
            if (!targets.equals(replacements.keySet())) throw new IllegalArgumentException("Unrelated replacements");
            var points = RhizomeGrowthPoints.active(replay);
            for (var entry : replacements.entrySet())
                if (!RhizomeGrowthPoints.mark(entry.getValue(), points.contains(entry.getKey())).equals(entry.getValue()))
                    throw new IllegalArgumentException("Horizontal endpoint marker does not match path");
        }
    }

    /**
     * 每步直行优先，受阻才从两侧合法候选选择；不回溯搜索，也不反复重抽已保存长度。
     * extraDependencies 是物种额外环境快照；全部回调必须只读，具体地下茎状态由 materializer 提供。
     */
    public static Result prepare(ServerLevel level, PlantRhizomeSystem expected, String arm,
            Map<BlockPos, BlockState> extraDependencies, Predicate<BlockState> validAttachment,
            Predicate<BlockState> legalEnvironment, BiFunction<BlockPos, BlockState, BlockState> materializer,
            RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        Objects.requireNonNull(validAttachment);
        Objects.requireNonNull(legalEnvironment);
        Objects.requireNonNull(materializer);
        Objects.requireNonNull(random);
        var systems = PlantRhizomeSystems.get(level);
        var individuals = PlantIndividuals.get(level);
        if (systems.isReadOnly() || individuals.isReadOnly()) return rejected(Reason.READ_ONLY);
        if (systems.isApplyingStructure() || individuals.isApplyingStructure()) return rejected(Reason.APPLYING_STRUCTURE);
        if (systems.needsRecovery(expected.id())) return rejected(Reason.RECOVERY_REQUIRED);
        if (!systems.find(expected.id()).filter(expected::equals).isPresent()) return rejected(Reason.STALE_SYSTEM);
        if (!expected.source().canExpand()) return rejected(Reason.SOURCE_DISABLED);
        var tip = expected.growth().map(growth -> growth.tip(arm)).orElse(null);
        if (tip == null || tip.remaining() == 0) return rejected(Reason.NO_BUDGET);
        Map<BlockPos, BlockState> reads = new LinkedHashMap<>(immutable(extraDependencies));
        for (var pos : reads.keySet()) if (!available(level, pos)) return rejected(Reason.WORLD_CHANGED);
        for (var entry : reads.entrySet())
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) return rejected(Reason.WORLD_CHANGED);
        if (!available(level, tip.position())) return rejected(Reason.ATTACHMENT_UNAVAILABLE);
        var attachment = level.getBlockState(tip.position());
        reads.put(tip.position(), attachment);
        if (!validAttachment.test(attachment) || !RhizomeOverwrite.ownsAttachment(level, expected, tip.position()))
            return rejected(Reason.INVALID_ATTACHMENT);
        if (expected.rhizomes().contains(tip.position()) && (!attachment.hasProperty(RhizomeBlock.GROWTH_POINT)
                || !attachment.getValue(RhizomeBlock.GROWTH_POINT))) return rejected(Reason.INVALID_ATTACHMENT);

        Map<BlockPos, Boolean> candidateCache = new LinkedHashMap<>();
        Predicate<BlockPos> legal = pos -> candidateCache.computeIfAbsent(pos.immutable(), key -> {
            if (!available(level, key)) return false;
            var state = level.getBlockState(key);
            reads.putIfAbsent(key, state);
            return state.equals(reads.get(key)) && RhizomeOverwrite.canConvert(systems, expected, key, state)
                    && individuals.atPart(key).isEmpty() && individuals.atBase(key).isEmpty()
                    && legalEnvironment.test(state);
        });
        var working = expected;
        List<Direction> directions = new ArrayList<>();
        Map<BlockPos, BlockState> overwritten = new LinkedHashMap<>();
        for (int step = 0; step < tip.remaining(); step++) {
            var choice = RhizomeGrowthSelection.select(working, arm, legal, random).orElse(null);
            if (choice == null) return rejected(Reason.PATH_BLOCKED);
            directions.add(choice.direction());
            overwritten.put(choice.target(), reads.get(choice.target()));
            working = choice.system();
        }
        var partners = RhizomeDecorationCleanup.partners(level, overwritten);
        if (partners.isEmpty()) return rejected(Reason.PATH_BLOCKED);
        for (var entry : partners.get().entrySet()) {
            var previous = reads.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) return rejected(Reason.WORLD_CHANGED);
        }
        Map<BlockPos, BlockState> replacements = new LinkedHashMap<>();
        var points = RhizomeGrowthPoints.active(working);
        overwritten.forEach((pos, before) -> replacements.put(pos, RhizomeGrowthPoints.mark(
                Objects.requireNonNull(materializer.apply(pos, before)), points.contains(pos))));
        if (!systems.find(expected.id()).filter(expected::equals).isPresent()) return rejected(Reason.STALE_SYSTEM);
        for (var entry : reads.entrySet())
            if (!available(level, entry.getKey()) || !level.getBlockState(entry.getKey()).equals(entry.getValue()))
                return rejected(Reason.WORLD_CHANGED);
        return new Result(Reason.READY, Optional.of(new Plan(expected, arm, directions, replacements, reads)));
    }

    /** 不重新选路或重抽随机数，沿用已有事务复核、无掉落清理、回滚和预算提交。 */
    public static PlantStructureCommitter.Status place(ServerLevel level, Plan plan,
            Predicate<BlockState> validAttachment, Predicate<BlockState> legalEnvironment) {
        return RhizomeGrowthPlacement.placePath(level, plan.expected(), plan.arm(), plan.directions(),
                plan.replacements(), plan.dependencies(), validAttachment, legalEnvironment);
    }

    /** 指定端受阻时可尝试同一个体的其他端；只换候选，不刷新任何植株的 C/D 预算。 */
    public static Result prepareAny(ServerLevel level, PlantRhizomeSystem expected,
            Map<BlockPos, BlockState> extraDependencies, Predicate<BlockState> validAttachment,
            Predicate<BlockState> legalEnvironment, BiFunction<BlockPos, BlockState, BlockState> materializer,
            RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        Objects.requireNonNull(random);
        var systems = PlantRhizomeSystems.get(level);
        var individuals = PlantIndividuals.get(level);
        // 在抽选端点前拒绝无权执行的调用，静止/过期/事务中的查询不推进随机序列。
        if (systems.isReadOnly() || individuals.isReadOnly()) return rejected(Reason.READ_ONLY);
        if (systems.isApplyingStructure() || individuals.isApplyingStructure()) return rejected(Reason.APPLYING_STRUCTURE);
        if (systems.needsRecovery(expected.id())) return rejected(Reason.RECOVERY_REQUIRED);
        if (!systems.find(expected.id()).filter(expected::equals).isPresent()) return rejected(Reason.STALE_SYSTEM);
        if (!expected.source().canExpand()) return rejected(Reason.SOURCE_DISABLED);
        if (expected.growth().isEmpty()) return rejected(Reason.NO_BUDGET);
        var growth = expected.growth().get();
        var arms = growth.allTips().entrySet().stream().filter(entry -> entry.getValue().remaining() > 0
                && (expected.rhizomes().contains(entry.getValue().position())
                    || expected.isFiberAnchor(entry.getValue().position())))
                .map(Map.Entry::getKey).sorted().toList();
        if (arms.isEmpty()) return rejected(Reason.NO_BUDGET);
        int start = arms.size() == 1 ? 0 : random.nextInt(arms.size());
        for (int i = 0; i < arms.size(); i++) {
            var result = prepare(level, expected, arms.get((start + i) % arms.size()), extraDependencies,
                    validAttachment, legalEnvironment, materializer, random);
            if (result.ready() || (result.reason() != Reason.PATH_BLOCKED
                    && result.reason() != Reason.INVALID_ATTACHMENT && result.reason() != Reason.ATTACHMENT_UNAVAILABLE)) return result;
        }
        return rejected(Reason.PATH_BLOCKED);
    }

    private static Map<BlockPos, BlockState> immutable(Map<BlockPos, BlockState> source) {
        Map<BlockPos, BlockState> copy = new LinkedHashMap<>();
        source.forEach((pos, state) -> copy.put(pos.immutable(), Objects.requireNonNull(state)));
        return Map.copyOf(copy);
    }
    private static boolean available(ServerLevel level, BlockPos pos) {
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.getChunkSource().hasChunk(x, z)
                && level.getChunkSource().isPositionTicking(ChunkPos.asLong(x, z));
    }
    private static Result rejected(Reason reason) { return new Result(reason, Optional.empty()); }
}
