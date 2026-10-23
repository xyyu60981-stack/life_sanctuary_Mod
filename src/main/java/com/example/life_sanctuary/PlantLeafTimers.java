package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.util.RandomSource;

/**
 * 每株一个补叶计时、每片采空叶一个脱落计时。持久化由 PlantLeafData 负责，活动进度由 PlantLeafScheduler 提供。
 * 暂停时调用方不推进计时；不因悬停查询、睡觉或区块重新加载自动补算。
 */
public record PlantLeafTimers(Optional<PlantLeafTimer> regrowth, Map<String, PlantLeafTimer> shedding) {
    public static final PlantLeafTimers EMPTY = new PlantLeafTimers(Optional.empty(), Map.of());

    public PlantLeafTimers {
        java.util.Objects.requireNonNull(regrowth);
        shedding = Map.copyOf(shedding);
        if (regrowth.filter(timer -> timer.kind() != PlantLeafTimer.Kind.REGROWTH).isPresent()
                || shedding.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank()
                    || entry.getValue().kind() != PlantLeafTimer.Kind.SHEDDING))
            throw new IllegalArgumentException("Independent leaf timer kind/key mismatch");
    }

    /** 重复通知不重抽；只允许完整人工生长来源，STATIC/ONE_TIME 不新开补生循环。 */
    public Optional<PlantLeafTimers> startRegrowth(PlantLeafSet leaves, PlantSource source, RandomSource random) {
        if (!source.canProgress() || leaves.freeLeafSlots() == 0) return Optional.empty();
        if (regrowth.isPresent()) return Optional.of(this);
        return Optional.of(new PlantLeafTimers(Optional.of(PlantLeafTimer.start(PlantLeafTimer.Kind.REGROWTH, random)), shedding));
    }

    public Optional<PlantLeafTimers> startShedding(PlantLeafSet leaves, String key, PlantSource source, RandomSource random) {
        var leaf = leaves.leaves().get(key);
        if (!source.canProgress() || leaf == null || !leaf.present() || !leaf.exhausted()) return Optional.empty();
        if (shedding.containsKey(key)) return Optional.of(this);
        var next = new HashMap<>(shedding);
        next.put(key, PlantLeafTimer.start(PlantLeafTimer.Kind.SHEDDING, random));
        return Optional.of(new PlantLeafTimers(regrowth, next));
    }

    /** 更新当前计时，不能借替换改变时长或清空异常标记。实际世界成功前不删除到期项。 */
    public Optional<PlantLeafTimers> replaceRegrowth(PlantLeafTimer expected, PlantLeafTimer next) {
        if (expected == null || next == null || !regrowth.filter(expected::equals).isPresent() || !expected.permits(next))
            return Optional.empty();
        return Optional.of(new PlantLeafTimers(Optional.of(next), shedding));
    }

    public Optional<PlantLeafTimers> replaceShedding(String key, PlantLeafTimer expected, PlantLeafTimer next) {
        if (expected == null || next == null || !expected.equals(shedding.get(key)) || !expected.permits(next))
            return Optional.empty();
        var updated = new HashMap<>(shedding);
        updated.put(key, next);
        return Optional.of(new PlantLeafTimers(regrowth, updated));
    }

    /** 结构提交后清理已经不适用的任务；不增加任务、不重抽时长、不清除叶资源。 */
    public PlantLeafTimers afterStructureChange(PlantLeafSet leaves) {
        var next = new HashMap<>(shedding);
        next.keySet().removeIf(key -> {
            var leaf = leaves.leaves().get(key);
            return leaf == null || !leaf.present() || !leaf.exhausted();
        });
        var nextRegrowth = leaves.freeLeafSlots() == 0 ? Optional.<PlantLeafTimer>empty() : regrowth;
        return next.equals(shedding) && nextRegrowth.equals(regrowth) ? this : new PlantLeafTimers(nextRegrowth, next);
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
        return Optional.of(new PlantLeafTimers(regrowth, next));
    }

    /**
     * 每次只承认一片新叶；失败保留到期项，不重新等 2～5 日。
     * 若成功后还有空位，再抽下一轮全株等待；新叶资源继承由后续世界事务另行校验。
     */
    public Optional<PlantLeafTimers> finishRegrowth(PlantLeafSet before, PlantLeafSet after,
            String newKey, PlantSource source, RandomSource random) {
        if (!source.canProgress() || regrowth.isEmpty() || !regrowth.get().ready()) return Optional.empty();
        var newLeaf = after.leaves().get(newKey);
        if (newLeaf == null || !before.addNew(newKey, newLeaf).filter(after::equals).isPresent()) return Optional.empty();
        return finishCycle(after, source, random);
    }

    public Optional<PlantLeafTimers> finishInheritedRegrowth(PlantLeafSet before, PlantLeafSet after,
            String retainedKey, String newKey, PlantSource source, RandomSource random) {
        if (!source.canProgress() || regrowth.isEmpty() || !regrowth.get().ready()) return Optional.empty();
        var newLeaf = after.leaves().get(newKey);
        if (newLeaf == null || !before.inheritRetained(retainedKey, newKey, newLeaf).filter(after::equals).isPresent())
            return Optional.empty();
        return finishCycle(after, source, random);
    }

    private Optional<PlantLeafTimers> finishCycle(PlantLeafSet after, PlantSource source, RandomSource random) {
        var next = new PlantLeafTimers(Optional.empty(), shedding);
        return after.freeLeafSlots() == 0 ? Optional.of(next) : next.startRegrowth(after, source, random);
    }
}
