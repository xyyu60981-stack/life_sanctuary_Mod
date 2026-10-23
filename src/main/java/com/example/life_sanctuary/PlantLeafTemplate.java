package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 按阶段选择的单方向叶片模板，坐标相对中心芽丛；ZERO 只表示中心，不生成叶方块。
 * 亚成体／成年体可使用相似模型但不同方块状态及占位，不从模型尺寸推断成年资格。
 * 不读取世界、不抽随机、不缩放或旋转模型；五向的具体几何由各方向模板提供。
 */
public record PlantLeafTemplate(BlockPos anchorOffset, Map<BlockPos, Part> parts) {
    public record Part(BlockState state, String suffix, PlantStructurePlan.Replacement policy,
            Set<BlockPos> supportOffsets) {
        public Part {
            java.util.Objects.requireNonNull(state);
            java.util.Objects.requireNonNull(suffix);
            java.util.Objects.requireNonNull(policy);
            if (state.isAir() || state.hasBlockEntity() || policy.requiresOwnership()
                    || !suffix.isEmpty() && (suffix.isBlank() || suffix.startsWith("/") || suffix.endsWith("/") || suffix.contains("//")))
                throw new IllegalArgumentException("Invalid leaf template part");
            var copied = new HashSet<BlockPos>();
            supportOffsets.forEach(pos -> copied.add(pos.immutable()));
            supportOffsets = Set.copyOf(copied);
            if (supportOffsets.isEmpty()) throw new IllegalArgumentException("Leaf template part needs internal support");
        }
    }

    public PlantLeafTemplate {
        anchorOffset = anchorOffset.immutable();
        Map<BlockPos, Part> copied = new HashMap<>();
        parts.forEach((pos, part) -> copied.put(pos.immutable(), java.util.Objects.requireNonNull(part)));
        parts = Map.copyOf(copied);
        var anchor = parts.get(anchorOffset);
        if (parts.containsKey(BlockPos.ZERO) || anchor == null || !anchor.suffix().isEmpty())
            throw new IllegalArgumentException("Leaf template needs a root anchor outside the center");
        Map<BlockPos, Set<BlockPos>> graph = new HashMap<>();
        graph.put(BlockPos.ZERO, Set.of());
        parts.forEach((pos, part) -> graph.put(pos, part.supportOffsets()));
        // 统一检查环、自支撑和未知父节点；单叶的所有部件只能最终支撑于中心。
        var supports = new PlantStructureSupports(graph);
        for (var pos : parts.keySet())
            if (!supports.hasSupportPath(pos, BlockPos.ZERO)) throw new IllegalArgumentException("Detached leaf template part");
    }

    /** 仅为拼装提供不可变数据；完整植株支撑和空间校验仍由共同事务执行。 */
    public record Prepared(BlockPos center, PlantLeafState leaf, Map<BlockPos, PlantStageStructure.Part> parts,
            Map<BlockPos, Set<BlockPos>> requiredSupports) {
        public Prepared {
            center = center.immutable();
            java.util.Objects.requireNonNull(leaf);
            Map<BlockPos, PlantStageStructure.Part> copiedParts = new HashMap<>();
            parts.forEach((pos, part) -> copiedParts.put(pos.immutable(), part));
            parts = Map.copyOf(copiedParts);
            Map<BlockPos, Set<BlockPos>> copied = new HashMap<>();
            requiredSupports.forEach((pos, parents) -> {
                var immutableParents = new HashSet<BlockPos>();
                parents.forEach(parent -> immutableParents.add(parent.immutable()));
                copied.put(pos.immutable(), Set.copyOf(immutableParents));
            });
            requiredSupports = Map.copyOf(copied);
            if (!parts.keySet().equals(requiredSupports.keySet()) || !parts.containsKey(leaf.anchor())
                    || !parts.get(leaf.anchor()).role().equals(leaf.role()))
                throw new IllegalArgumentException("Incomplete prepared leaf structure");
        }
    }

    /** 角色根标识方向，资源键由阶段／补叶适配器另外指定；模板不会自动发次数。 */
    public Prepared prepare(BlockPos center, String role, boolean fertile, int remainingCollections) {
        if (role == null || role.isBlank() || role.startsWith("/") || role.endsWith("/") || role.contains("//"))
            throw new IllegalArgumentException("Invalid leaf direction role");
        Map<BlockPos, PlantStageStructure.Part> placed = new HashMap<>();
        Map<BlockPos, Set<BlockPos>> supports = new HashMap<>();
        parts.forEach((offset, part) -> {
            BlockPos pos = center.offset(offset);
            placed.put(pos, new PlantStageStructure.Part(part.state(),
                    part.suffix().isEmpty() ? role : role + "/" + part.suffix(), part.policy()));
            var parents = new HashSet<BlockPos>();
            part.supportOffsets().forEach(parent -> parents.add(center.offset(parent)));
            supports.put(pos, Set.copyOf(parents));
        });
        return new Prepared(center, new PlantLeafState(center.offset(anchorOffset), role, true, fertile, remainingCollections),
                placed, supports);
    }
}
