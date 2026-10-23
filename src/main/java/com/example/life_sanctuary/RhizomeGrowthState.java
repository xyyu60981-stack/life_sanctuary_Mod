package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

/** 两个地下生长端的持久化预算；C 初始长度和 D 追加长度仅在阶段启动时抽取一次。 */
public record RhizomeGrowthState(boolean dStarted, Map<String, Tip> tips, Map<UUID, Branch> branches) {
    public RhizomeGrowthState(boolean dStarted, Map<String, Tip> tips) { this(dStarted, tips, Map.of()); }
    public record Branch(BlockPos root, RhizomeGrowthState growth) {
        public Branch {
            root = root.immutable();
            java.util.Objects.requireNonNull(growth);
            if (!growth.branches().isEmpty()) throw new IllegalArgumentException("Nested rhizome branches are not allowed");
        }
    }
    public record Tip(BlockPos position, Direction direction, int remaining) {
        public Tip {
            position = position.immutable();
            if (direction.getAxis() == Direction.Axis.Y || remaining < 0)
                throw new IllegalArgumentException("Invalid rhizome growth tip");
        }
    }
    public RhizomeGrowthState {
        tips = Map.copyOf(tips);
        branches = Map.copyOf(branches);
        if (tips.size() != 2 || !tips.containsKey("positive") || !tips.containsKey("negative"))
            throw new IllegalArgumentException("Rhizome growth requires two stable arm keys");
        if (tips.values().stream().anyMatch(tip -> tip.remaining() > (dStarted ? 10 : 5)))
            throw new IllegalArgumentException("Rhizome growth budget exceeds C/D allowances");
    }

    public static RhizomeGrowthState initialC(BlockPos origin, Direction axis, RandomSource random) {
        java.util.Objects.requireNonNull(random);
        if (axis.getAxis() == Direction.Axis.Y) throw new IllegalArgumentException("Rhizome axis must be horizontal");
        return new RhizomeGrowthState(false, Map.of(
                "positive", new Tip(origin, axis, 3 + random.nextInt(3)),
                "negative", new Tip(origin, axis.getOpposite(), 3 + random.nextInt(3))));
    }

    public static RhizomeGrowthState initialC(BlockPos origin, RandomSource random) {
        return initialC(origin, random.nextBoolean() ? Direction.EAST : Direction.SOUTH, random);
    }

    /** D 每端各追加 2～5，未用完的 C 长度保留；重复请求不投随机数。 */
    public Optional<RhizomeGrowthState> beginD(RandomSource random) {
        if (dStarted) return Optional.empty();
        Map<String, Tip> next = new HashMap<>();
        for (String key : tips.keySet().stream().sorted().toList()) {
            Tip tip = tips.get(key);
            next.put(key, new Tip(tip.position(), tip.direction(), Math.addExact(tip.remaining(), 2 + random.nextInt(4))));
        }
        return Optional.of(new RhizomeGrowthState(true, next, branches));
    }

    public Map<String, Tip> allTips() {
        Map<String, Tip> result = new HashMap<>(tips);
        branches.forEach((id, branch) -> branch.growth().tips().forEach((key, tip) -> result.put(id + "/" + key, tip)));
        return Map.copyOf(result);
    }

    public Tip tip(String arm) {
        if (tips.containsKey(arm)) return tips.get(arm);
        int separator = arm.indexOf('/');
        if (separator < 0) return null;
        try {
            var branch = branches.get(UUID.fromString(arm.substring(0, separator)));
            return branch == null ? null : branch.growth().tips().get(arm.substring(separator + 1));
        } catch (IllegalArgumentException invalid) { return null; }
    }

    public BlockPos rootFor(String arm, BlockPos primaryRoot) {
        if (tips.containsKey(arm)) return primaryRoot;
        int separator = arm.indexOf('/');
        if (separator < 0) throw new IllegalArgumentException("Unknown rhizome arm");
        var branch = branches.get(UUID.fromString(arm.substring(0, separator)));
        if (branch == null) throw new IllegalArgumentException("Unknown rhizome branch");
        return branch.root();
    }

    public Optional<RhizomeGrowthState> addBranch(UUID plantId, BlockPos root, RandomSource random) {
        if (branches.containsKey(plantId)) return Optional.empty();
        Map<UUID, Branch> next = new HashMap<>(branches);
        next.put(plantId, new Branch(root, initialC(root, random)));
        return Optional.of(new RhizomeGrowthState(dStarted, tips, next));
    }

    public Optional<RhizomeGrowthState> beginBranchD(UUID plantId, RandomSource random) {
        var branch = branches.get(plantId);
        if (branch == null) return Optional.empty();
        return branch.growth().beginD(random).map(growth -> {
            Map<UUID, Branch> next = new HashMap<>(branches);
            next.put(plantId, new Branch(branch.root(), growth));
            return new RhizomeGrowthState(dStarted, tips, next);
        });
    }

    Optional<RhizomeGrowthState> advance(String arm, Direction direction) {
        if (!tips.containsKey(arm)) {
            int separator = arm.indexOf('/');
            if (separator < 0) return Optional.empty();
            UUID id;
            try { id = UUID.fromString(arm.substring(0, separator)); }
            catch (IllegalArgumentException invalid) { return Optional.empty(); }
            var branch = branches.get(id);
            if (branch == null) return Optional.empty();
            return branch.growth().advance(arm.substring(separator + 1), direction).map(growth -> {
                Map<UUID, Branch> next = new HashMap<>(branches);
                next.put(id, new Branch(branch.root(), growth));
                return new RhizomeGrowthState(dStarted, tips, next);
            });
        }
        Tip tip = tips.get(arm);
        if (tip == null || tip.remaining() == 0 || direction.getAxis() == Direction.Axis.Y
                || direction == tip.direction().getOpposite()) return Optional.empty();
        Map<String, Tip> next = new HashMap<>(tips);
        next.put(arm, new Tip(tip.position().relative(direction), direction, tip.remaining() - 1));
        return Optional.of(new RhizomeGrowthState(dStarted, next, branches));
    }
}
