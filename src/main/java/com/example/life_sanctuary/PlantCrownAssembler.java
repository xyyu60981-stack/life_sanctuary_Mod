package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** 将阶段核心与各方向模板拼成完整提案；不读世界、不随机、不裁掉受阻方向。 */
public final class PlantCrownAssembler {
    private PlantCrownAssembler() {}

    public record Proposal(PlantStageStructure structure, PlantInitialCrown crown) {}

    /**
     * core 包含完整目标树干／芽丛／需保留的裙等非活叶部件；coreSupports 仅包含这些部件。
     * directions 的键为本轮全新叶资源键，须列全候选方向，不能先裁掉受阻方向。
     * 额度及阶段资格仍由共同事务验证；fertileLimit 来自物种配置，不按模型大小推断。
     */
    public static Proposal assemble(String stage, BlockPos base, BlockPos center,
            Map<BlockPos, PlantStageStructure.Part> core, PlantStructureSupports coreSupports,
            Map<String, PlantLeafTemplate.Prepared> directions, int fertileLimit) {
        if (directions.isEmpty() || !core.keySet().equals(coreSupports.positions())
                || !core.containsKey(base) || !core.containsKey(center)
                || !coreSupports.hasSupportPath(center, base))
            throw new IllegalArgumentException("Crown assembly needs a complete supported core and all leaf directions");
        Map<BlockPos, PlantStageStructure.Part> parts = new HashMap<>();
        core.forEach((pos, part) -> parts.put(pos.immutable(), part));
        Map<BlockPos, Set<BlockPos>> graph = new HashMap<>(coreSupports.requiredSupports());
        Map<String, PlantLeafState> leaves = new HashMap<>();
        Set<String> roles = new HashSet<>();
        for (var key : directions.keySet().stream().sorted().toList()) {
            if (key.isBlank()) throw new IllegalArgumentException("Missing crown resource key");
            var prepared = directions.get(key);
            var leaf = prepared.leaf();
            if (!prepared.center().equals(center) || !leaf.present() || leaf.fertile() && leaf.remainingCollections() == 0
                    || !roles.add(leaf.role())) throw new IllegalArgumentException("Invalid crown direction");
            for (var entry : prepared.parts().entrySet()) {
                var pos = entry.getKey();
                var part = entry.getValue();
                if (!(part.role().equals(leaf.role()) || part.role().startsWith(leaf.role() + "/"))
                        || parts.putIfAbsent(pos, part) != null)
                    throw new IllegalArgumentException("Overlapping crown parts or unrelated leaf role");
                var parents = prepared.requiredSupports().get(pos);
                if (parents == null || parents.isEmpty() || parents.stream().anyMatch(parent ->
                        !parent.equals(center) && !prepared.parts().containsKey(parent)))
                    throw new IllegalArgumentException("A crown leaf may depend only on itself and its center");
                graph.put(pos, parents);
            }
            leaves.put(key, leaf);
        }
        var leafSet = new PlantLeafSet(directions.size(), fertileLimit, leaves, roles);
        var leafIndex = new PlantLeafPartIndex(leafSet);
        // 角色冲突同样危险：核心格不能使用活叶角色，即使其坐标与叶片不重叠。
        for (var part : core.values())
            if (leafIndex.keyForRole(part.role()).isPresent())
                throw new IllegalArgumentException("Non-leaf core uses a live leaf role");
        var supports = new PlantStructureSupports(graph);
        if (!supports.positions().equals(parts.keySet())) throw new IllegalArgumentException("Incomplete assembled crown support graph");
        return new Proposal(new PlantStageStructure(stage, parts),
                new PlantInitialCrown(new PlantLeafData(leafSet, PlantLeafTimers.EMPTY), center, supports));
    }
}
