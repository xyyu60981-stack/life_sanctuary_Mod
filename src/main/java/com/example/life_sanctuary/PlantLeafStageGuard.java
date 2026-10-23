package com.example.life_sanctuary;

import java.util.Map;
import net.minecraft.core.BlockPos;

/** 普通换阶段只允许保留叶账本；换冠、切叶与补叶必须走专用事务。 */
final class PlantLeafStageGuard {
    private PlantLeafStageGuard() {}

    static boolean preserves(PlantLeafData leaves, Map<BlockPos, String> owned,
            PlantStructurePlan plan, PlantIndividualState next) {
        for (var entry : leaves.leaves().leaves().entrySet()) {
            // 历史零额度键也不可借阶段首次额度重新分配。
            if (next.harvests().containsKey(entry.getKey()) || next.reproduction().containsKey(entry.getKey())) return false;
            var leaf = entry.getValue();
            if (!leaf.present()) continue;
            if (!leaf.role().equals(owned.get(leaf.anchor()))) return false;
            for (var part : owned.entrySet()) {
                if (!belongs(part.getValue(), leaf.role())) continue;
                var change = plan.changes().get(part.getKey());
                if (change != null && !part.getValue().equals(change.role())) return false;
                if (change == null && !plan.dependencies().containsKey(part.getKey())) return false;
            }
        }
        // 叶角色命名空间包含历史记录，不能趁换阶段为旧叶悄悄添加新部件。
        for (var entry : plan.changes().entrySet()) {
            var change = entry.getValue();
            if (leaves.leaves().leaves().values().stream().anyMatch(leaf -> belongs(change.role(), leaf.role()))
                    && !change.role().equals(owned.get(entry.getKey()))) return false;
        }
        return true;
    }

    private static boolean belongs(String role, String leafRole) {
        return role.equals(leafRole) || role.startsWith(leafRole + "/");
    }
}
