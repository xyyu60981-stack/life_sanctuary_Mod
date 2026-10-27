package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.util.RandomSource;

/**
 * 每芽丛一个补叶计时、每片采空叶一个脱落计时；旧单芽丛账本保留全株兼容计时。
 * 暂停时调用方不推进计时；不因悬停查询、睡觉或区块重新加载自动补算。
 */
public record PlantLeafTimers(Optional<PlantLeafTimer> regrowth, Map<String, PlantLeafTimer> shedding, Map<String, PlantLeafTimer> crownRegrowth) {
    public PlantLeafTimers(Optional<PlantLeafTimer> regrowth, Map<String, PlantLeafTimer> shedding) { this(regrowth, shedding, Map.of()); }
    public static final PlantLeafTimers EMPTY = new PlantLeafTimers(Optional.empty(), Map.of());

    public PlantLeafTimers {
        java.util.Objects.requireNonNull(regrowth);
        shedding = Map.copyOf(shedding);
        crownRegrowth = Map.copyOf(crownRegrowth);
        if (regrowth.isPresent() && !crownRegrowth.isEmpty() || crownRegrowth.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().kind() != PlantLeafTimer.Kind.REGROWTH))
            throw new IllegalArgumentException("Invalid grouped crown timers");
        if (regrowth.filter(timer -> timer.kind() != PlantLeafTimer.Kind.REGROWTH).isPresent()
                || shedding.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank()
                    || entry.getValue().kind() != PlantLeafTimer.Kind.SHEDDING))
            throw new IllegalArgumentException("Independent leaf timer kind/key mismatch");
    }

    /** 重复通知不重抽；只允许完整人工生长来源，STATIC/ONE_TIME 不新开补生循环。 */
    public Optional<PlantLeafTimers> startRegrowth(PlantLeafSet leaves, PlantSource source, RandomSource random) {
        if (!source.canProgress() || leaves.freeLeafSlots() == 0 || !leaves.crowns().isEmpty()) return Optional.empty();
        if (regrowth.isPresent()) return Optional.of(this);
        return Optional.of(new PlantLeafTimers(Optional.of(PlantLeafTimer.start(PlantLeafTimer.Kind.REGROWTH, random)), shedding));
    }

    /** 玩家实际切去另一片叶时重新开始；悬停、重复通知、自动脱落不走此入口。 */
    public Optional<PlantLeafTimers> restartRegrowth(PlantLeafSet leaves, PlantSource source, RandomSource random) {
        if (!source.canProgress() || leaves.freeLeafSlots() == 0 || !leaves.crowns().isEmpty()) return Optional.empty();
        if (regrowth.filter(PlantLeafTimer::failed).isPresent()) return Optional.empty();
        return Optional.of(new PlantLeafTimers(Optional.of(PlantLeafTimer.start(PlantLeafTimer.Kind.REGROWTH, random)), shedding));
    }

    public Optional<PlantLeafTimers> regrowthAfterLoss(PlantLeafSet leaves, String leafRole, PlantSource source,
            RandomSource random, boolean manual) {
        if (!source.canProgress()) return Optional.empty();
        var crown = leaves.crownOf(leafRole);
        if (crown.isEmpty()) return manual ? restartRegrowth(leaves, source, random) : startRegrowth(leaves, source, random);
        if (regrowth.isPresent() || leaves.freeCrownSlots(crown.get()) == 0) return Optional.empty();
        if (manual && Optional.ofNullable(crownRegrowth.get(crown.get())).filter(PlantLeafTimer::failed).isPresent()) return Optional.empty();
        if (!manual && crownRegrowth.containsKey(crown.get())) return Optional.of(this);
        var next = new HashMap<>(crownRegrowth);
        next.put(crown.get(), PlantLeafTimer.start(PlantLeafTimer.Kind.REGROWTH, random));
        return Optional.of(new PlantLeafTimers(Optional.empty(), shedding, next));
    }

    public Optional<PlantLeafTimer> regrowthFor(PlantLeafSet leaves, String leafRole) {
        var crown = leaves.crownOf(leafRole);
        return crown.isPresent() ? Optional.ofNullable(crownRegrowth.get(crown.get())) : regrowth;
    }

    public PlantLeafTimers completeCrown(PlantLeafSet after, Optional<String> crown) {
        if (crown.isEmpty()) return after.freeLeafSlots() == 0 ? new PlantLeafTimers(Optional.empty(), shedding) : this;
        var next = new HashMap<>(crownRegrowth);
        if (after.freeCrownSlots(crown.get()) == 0) next.remove(crown.get());
        return new PlantLeafTimers(Optional.empty(), shedding, next);
    }

    public Optional<PlantLeafTimers> startShedding(PlantLeafSet leaves, String key, PlantSource source, RandomSource random) {
        var leaf = leaves.leaves().get(key);
        if (!source.canProgress() || leaf == null || !leaf.present() || !leaf.exhausted()) return Optional.empty();
        if (shedding.containsKey(key)) return Optional.of(this);
        var next = new HashMap<>(shedding);
        next.put(key, PlantLeafTimer.start(PlantLeafTimer.Kind.SHEDDING, random));
        return Optional.of(new PlantLeafTimers(regrowth, next, crownRegrowth));
    }

    /** 更新当前计时，不能借替换改变时长或清空异常标记。实际世界成功前不删除到期项。 */
    public Optional<PlantLeafTimers> replaceRegrowth(PlantLeafTimer expected, PlantLeafTimer next) {
        if (expected == null || next == null || !regrowth.filter(expected::equals).isPresent() || !expected.permits(next))
            return Optional.empty();
        return Optional.of(new PlantLeafTimers(Optional.of(next), shedding, crownRegrowth));
    }

    public Optional<PlantLeafTimers> replaceShedding(String key, PlantLeafTimer expected, PlantLeafTimer next) {
        if (expected == null || next == null || !expected.equals(shedding.get(key)) || !expected.permits(next))
            return Optional.empty();
        var updated = new HashMap<>(shedding);
        updated.put(key, next);
        return Optional.of(new PlantLeafTimers(regrowth, updated, crownRegrowth));
    }

    /** 结构提交后清理已经不适用的任务；不增加任务、不重抽时长、不清除叶资源。 */
    public PlantLeafTimers afterStructureChange(PlantLeafSet leaves) {
        var next = new HashMap<>(shedding);
        next.keySet().removeIf(key -> {
            var leaf = leaves.leaves().get(key);
            return leaf == null || !leaf.present() || !leaf.exhausted();
        });
        var nextRegrowth = leaves.freeLeafSlots() == 0 ? Optional.<PlantLeafTimer>empty() : regrowth;
        var crowns = new HashMap<>(crownRegrowth);
        crowns.keySet().removeIf(key -> !leaves.crowns().containsKey(key) || leaves.freeCrownSlots(key) == 0);
        return next.equals(shedding) && nextRegrowth.equals(regrowth) && crowns.equals(crownRegrowth)
                ? this : new PlantLeafTimers(nextRegrowth, next, crowns);
    }

    /** 仅在对应叶片已通过结构事务成功消失后调用；不删除叶资源记录。 */
    public Optional<PlantLeafTimers> finishShedding(PlantLeafSet before, PlantLeafSet after, String key) {
        var timer = shedding.get(key);
        var old = before.leaves().get(key);
        if (timer == null || !timer.ready() || old == null || !old.present() || !old.exhausted()
                || !before.replace(key, old, old.withoutStructure()).filter(after::equals).isPresent())
            return Optional.empty();
        var next = new HashMap<>(shedding);
        next.remove(key);
        return Optional.of(new PlantLeafTimers(regrowth, next, crownRegrowth));
    }

    /**
     * 兼容单片提交：同一到期批次的其余缺叶继续保持到期，不再为每片重等2～5日。
     * 同芽丛全部补齐应使用批量入口；新叶资源继承由世界事务校验。
     */
    public Optional<PlantLeafTimers> finishRegrowth(PlantLeafSet before, PlantLeafSet after,
            String newKey, PlantSource source, RandomSource random) {
        var newLeaf = after.leaves().get(newKey);
        if (!source.canProgress() || newLeaf == null || regrowthFor(before, newLeaf.role()).filter(PlantLeafTimer::ready).isEmpty()) return Optional.empty();
        if (newLeaf == null || !before.addNew(newKey, newLeaf).filter(after::equals).isPresent()) return Optional.empty();
        return Optional.of(completeCrown(after, after.crownOf(newLeaf.role())));
    }

    public Optional<PlantLeafTimers> finishInheritedRegrowth(PlantLeafSet before, PlantLeafSet after,
            String retainedKey, String newKey, PlantSource source, RandomSource random) {
        var newLeaf = after.leaves().get(newKey);
        if (!source.canProgress() || newLeaf == null || regrowthFor(before, newLeaf.role()).filter(PlantLeafTimer::ready).isEmpty()) return Optional.empty();
        if (newLeaf == null || !before.inheritRetained(retainedKey, newKey, newLeaf).filter(after::equals).isPresent())
            return Optional.empty();
        return Optional.of(completeCrown(after, after.crownOf(newLeaf.role())));
    }
}
