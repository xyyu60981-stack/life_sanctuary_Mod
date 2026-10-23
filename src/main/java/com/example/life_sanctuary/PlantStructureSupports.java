package com.example.life_sanctuary;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * 显式部件支撑图。每个部件要求列出的全部内部支撑存在；空集合表示独立基部。
 * 不从方块相邻关系猜测树形，不代表外部泥土/水分等生态条件，也不逐 tick 扫描。
 */
public final class PlantStructureSupports {
    private final Map<BlockPos, Set<BlockPos>> requiredSupports;
    private final Map<BlockPos, Set<BlockPos>> dependents;

    public PlantStructureSupports(Map<BlockPos, Set<BlockPos>> requiredSupports) {
        if (requiredSupports.isEmpty()) throw new IllegalArgumentException("Empty support graph");
        Map<BlockPos, Set<BlockPos>> copied = new HashMap<>();
        requiredSupports.forEach((pos, parents) -> {
            Set<BlockPos> immutableParents = new HashSet<>();
            parents.forEach(parent -> immutableParents.add(parent.immutable()));
            copied.put(pos.immutable(), Set.copyOf(immutableParents));
        });
        Map<BlockPos, Set<BlockPos>> reverse = new HashMap<>();
        Map<BlockPos, Integer> incoming = new HashMap<>();
        copied.forEach((pos, parents) -> {
            if (parents.contains(pos) || !copied.keySet().containsAll(parents))
                throw new IllegalArgumentException("Self support or unknown supporting part");
            incoming.put(pos, parents.size());
            for (BlockPos parent : parents) reverse.computeIfAbsent(parent, ignored -> new HashSet<>()).add(pos);
        });
        // 迭代检测环，避免大型树形递归造成栈溢出。
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        incoming.forEach((pos, count) -> { if (count == 0) pending.add(pos); });
        int visited = 0;
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            visited++;
            for (BlockPos child : reverse.getOrDefault(current, Set.of()))
                if (incoming.compute(child, (ignored, count) -> count - 1) == 0) pending.addLast(child);
        }
        if (visited != copied.size()) throw new IllegalArgumentException("Cyclic plant supports");
        Map<BlockPos, Set<BlockPos>> immutableReverse = new HashMap<>();
        reverse.forEach((pos, children) -> immutableReverse.put(pos, Set.copyOf(children)));
        this.requiredSupports = Map.copyOf(copied);
        this.dependents = Map.copyOf(immutableReverse);
    }

    public Set<BlockPos> positions() { return requiredSupports.keySet(); }
    public Map<BlockPos, Set<BlockPos>> requiredSupports() { return requiredSupports; }

    /** 按需验证部件到指定内部支撑的路径，不读取世界；同一已登记部件视为零长路径。 */
    public boolean hasSupportPath(BlockPos part, BlockPos support) {
        if (!requiredSupports.containsKey(part) || !requiredSupports.containsKey(support)) return false;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        pending.add(part);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current.equals(support)) return true;
            pending.addAll(requiredSupports.get(current));
        }
        return false;
    }

    /** 任一必需支撑被移除即连带失效；每个受影响部件最多加入队列一次。 */
    public Set<BlockPos> affectedBy(Set<BlockPos> removed) {
        if (!positions().containsAll(removed)) throw new IllegalArgumentException("Removed part is not in support graph");
        Set<BlockPos> affected = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        removed.forEach(pos -> { if (affected.add(pos.immutable())) pending.add(pos.immutable()); });
        while (!pending.isEmpty()) {
            for (BlockPos child : dependents.getOrDefault(pending.removeFirst(), Set.of()))
                if (affected.add(child)) pending.addLast(child);
        }
        return Set.copyOf(affected);
    }

    /** 返回剩余支撑图；整株移除返回空，不自动删除个体或生成掉落。 */
    public Optional<PlantStructureSupports> afterRemoving(Set<BlockPos> removed) {
        Set<BlockPos> affected = affectedBy(removed);
        Map<BlockPos, Set<BlockPos>> remaining = new HashMap<>(requiredSupports);
        affected.forEach(remaining::remove);
        return remaining.isEmpty() ? Optional.empty() : Optional.of(new PlantStructureSupports(remaining));
    }
}
