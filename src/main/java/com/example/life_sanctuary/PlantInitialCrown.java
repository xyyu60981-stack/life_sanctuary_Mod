package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** 阶段首次生叶的提案；中心可与树干一起新建，不能替代已有叶账本。 */
public record PlantInitialCrown(PlantLeafData leaves, BlockPos center, PlantStructureSupports supports) {
    public PlantInitialCrown {
        java.util.Objects.requireNonNull(leaves);
        center = center.immutable();
        java.util.Objects.requireNonNull(supports);
        if (leaves.leaves().leaves().isEmpty() || !leaves.timers().equals(PlantLeafTimers.EMPTY))
            throw new IllegalArgumentException("Initial crown needs fresh leaves without timers");
    }

    /** 纯快照校验，在空间预检和时长抽取之前调用，不读取世界。 */
    boolean valid(PlantIndividualState owner, Map<BlockPos, String> owned, PlantStructurePlan plan) {
        if (!plan.dependencies().keySet().containsAll(owned.keySet())) return false;
        var target = new HashMap<>(owned);
        plan.changes().forEach((pos, change) -> {
            if (change.role().isEmpty()) target.remove(pos); else target.put(pos, change.role());
        });
        if (!target.containsKey(owner.base()) || !target.containsKey(center)
                || !supports.positions().equals(target.keySet())
                || !supports.hasSupportPath(center, owner.base())) return false;
        var covered = new HashSet<BlockPos>();
        for (var leaf : leaves.leaves().leaves().values()) {
            if (!leaf.present() || leaf.fertile() && leaf.remainingCollections() == 0) return false;
            if (owned.values().stream().anyMatch(role -> belongs(role, leaf.role()))) return false;
            var anchor = plan.changes().get(leaf.anchor());
            if (anchor == null || !anchor.role().equals(leaf.role())) return false;
            var positions = new HashSet<BlockPos>();
            target.forEach((pos, role) -> { if (belongs(role, leaf.role())) positions.add(pos); });
            for (var pos : positions) {
                // 不把现有活部件更名为叶片并补发额度，即使方块外观正好相同。
                if (owned.containsKey(pos) || !covered.add(pos) || pos.equals(center) || pos.equals(owner.base())) return false;
                var parents = supports.requiredSupports().get(pos);
                if (parents == null || parents.isEmpty()
                        || parents.stream().anyMatch(parent -> !parent.equals(center) && !positions.contains(parent))) return false;
            }
        }
        for (var entry : supports.requiredSupports().entrySet())
            if (!covered.contains(entry.getKey()) && entry.getValue().stream().anyMatch(covered::contains)) return false;
        leaves.validateOwner(owner, target);
        return true;
    }

    private static boolean belongs(String role, String root) { return role.equals(root) || role.startsWith(root + "/"); }
}
