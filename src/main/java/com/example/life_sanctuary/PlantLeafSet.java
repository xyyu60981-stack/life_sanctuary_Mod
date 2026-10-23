package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

/**
 * 单株独立叶名额账本的不可变模型，持久化由 PlantIndividuals 的可选叶数据负责。
 * 稳定资源键一经出现即保留，包括零额度和已切除记录，禁止以重新放置刷新旧键。
 */
public record PlantLeafSet(int leafLimit, int fertileLimit, Map<String, PlantLeafState> leaves,
        java.util.Set<String> allowedRoles) {
    /** 兼容尚未限定方向的物种；空集合表示没有额外的角色白名单。 */
    public PlantLeafSet(int leafLimit, int fertileLimit, Map<String, PlantLeafState> leaves) {
        this(leafLimit, fertileLimit, leaves, java.util.Set.of());
    }
    public PlantLeafSet {
        leaves = Map.copyOf(leaves);
        allowedRoles = java.util.Set.copyOf(allowedRoles);
        if (allowedRoles.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Blank allowed leaf role");
        if (leafLimit < 1 || fertileLimit < 0 || fertileLimit > leafLimit)
            throw new IllegalArgumentException("Invalid independent leaf limits");
        var occupied = new HashSet<net.minecraft.core.BlockPos>();
        var liveRoles = new HashSet<String>();
        int present = 0, reserved = 0;
        for (var entry : leaves.entrySet()) {
            if (entry.getKey().isBlank()) throw new IllegalArgumentException("Missing stable leaf resource key");
            var leaf = entry.getValue();
            if (leaf.present() && !allowedRoles.isEmpty() && !allowedRoles.contains(leaf.role()))
                throw new IllegalArgumentException("Leaf role is disabled for this stage");
            if (leaf.present()) {
                present++;
                if (!occupied.add(leaf.anchor())) throw new IllegalArgumentException("Overlapping leaf anchors");
                if (!liveRoles.add(leaf.role())) throw new IllegalArgumentException("Duplicate live leaf role");
            }
            if (leaf.reservesFertileSlot()) reserved++;
        }
        if (present > leafLimit || reserved > fertileLimit)
            throw new IllegalArgumentException("Independent leaf allowance exceeded");
        // 仅检查活叶根，历史叶允许复用同一方向；不能只检查排序邻项，标点可能夹在父/子角色之间。
        for (var role : liveRoles) {
            int slash = role.lastIndexOf('/');
            while (slash >= 0) {
                if (liveRoles.contains(role.substring(0, slash)))
                    throw new IllegalArgumentException("Overlapping live leaf role namespaces");
                slash = role.lastIndexOf('/', slash - 1);
            }
        }
    }

    public int presentLeaves() { return (int) leaves.values().stream().filter(PlantLeafState::present).count(); }
    public int reservedFertileSlots() { return (int) leaves.values().stream().filter(PlantLeafState::reservesFertileSlot).count(); }
    public int freeLeafSlots() { return leafLimit - presentLeaves(); }
    public int freeFertileSlots() { return fertileLimit - reservedFertileSlots(); }

    /** 仅用于首次结构或新叶提案；实际次数和可育掷骰必须由物种策略明确提供。 */
    public Optional<PlantLeafSet> addNew(String key, PlantLeafState leaf) {
        if (key == null || key.isBlank() || leaves.containsKey(key) || !leaf.present()
                || !allowsRole(leaf.role())
                || freeLeafSlots() == 0 || leaf.reservesFertileSlot() && freeFertileSlots() == 0
                || leaves.values().stream().anyMatch(old -> old.present() && old.anchor().equals(leaf.anchor())))
            return Optional.empty();
        Map<String, PlantLeafState> next = new HashMap<>(leaves);
        next.put(key, leaf);
        return Optional.of(new PlantLeafSet(leafLimit, fertileLimit, next, allowedRoles));
    }

    /** 过期快照或非法刷新均拒绝；当前方法只返回新账本，不改变存储或方块。 */
    public Optional<PlantLeafSet> replace(String key, PlantLeafState expected, PlantLeafState replacement) {
        if (expected == null || replacement == null || !expected.equals(leaves.get(key))
                || !expected.permits(replacement)) return Optional.empty();
        if (expected.equals(replacement)) return Optional.of(this);
        Map<String, PlantLeafState> next = new HashMap<>(leaves);
        next.put(key, replacement);
        return Optional.of(new PlantLeafSet(leafLimit, fertileLimit, next, allowedRoles));
    }

    /** 将一份被砍叶的剩余次数完整转给新叶；旧键保留为零，名额与总次数不增加。 */
    public Optional<PlantLeafSet> inheritRetained(String retainedKey, String newKey, PlantLeafState leaf) {
        var old = leaves.get(retainedKey);
        if (old == null || old.present() || !old.reservesFertileSlot() || newKey == null || newKey.isBlank()
                || !allowsRole(leaf.role())
                || leaves.containsKey(newKey) || !leaf.present() || !leaf.fertile()
                || leaf.remainingCollections() != old.remainingCollections() || freeLeafSlots() == 0
                || leaves.values().stream().anyMatch(value -> value.present() && value.anchor().equals(leaf.anchor())))
            return Optional.empty();
        Map<String, PlantLeafState> next = new HashMap<>(leaves);
        next.put(retainedKey, new PlantLeafState(old.anchor(), old.role(), false, true, 0));
        next.put(newKey, leaf);
        return Optional.of(new PlantLeafSet(leafLimit, fertileLimit, next, allowedRoles));
    }

    public boolean allowsRole(String role) { return allowedRoles.isEmpty() || allowedRoles.contains(role); }
}
