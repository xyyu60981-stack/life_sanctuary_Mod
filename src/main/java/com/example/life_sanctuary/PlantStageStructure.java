package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/** 完整目标阶段结构；物种负责形状与生态规则，共享层负责完整差异和安全提交。 */
public record PlantStageStructure(String stage, Map<BlockPos, Part> parts) {
    public record Part(BlockState state, String role, PlantStructurePlan.Replacement newPositionPolicy) {
        public Part {
            Objects.requireNonNull(state);
            Objects.requireNonNull(role);
            Objects.requireNonNull(newPositionPolicy);
            if (state.isAir() || role.isBlank() || (newPositionPolicy.requiresOwnership()
                    && !newPositionPolicy.requiresRhizomeOwnership()))
                throw new IllegalArgumentException("Stage part needs a block, role and new-position policy");
        }
    }

    public PlantStageStructure {
        if (stage == null || stage.isBlank() || parts.isEmpty())
            throw new IllegalArgumentException("Stage structure cannot be empty");
        Map<BlockPos, Part> copied = new LinkedHashMap<>();
        parts.forEach((pos, part) -> copied.put(pos.immutable(), Objects.requireNonNull(part)));
        parts = java.util.Collections.unmodifiableMap(copied);
    }

    /**
     * observed 必须覆盖全部旧结构、目标结构及物种需要的基底/支撑读取依赖。
     * 此方法不读取世界；适配器捕获快照前须确认区块可用，不得强制加载。
     * 目标结构中省略的旧部件会被移除，不是隐式保留。
     */
    public PlantStructurePlan plan(PlantIndividualState expected, Map<BlockPos, String> ownedParts,
            Map<BlockPos, BlockState> observed) {
        if (!parts.containsKey(expected.base()))
            throw new IllegalArgumentException("Stage structure must retain the individual base");
        if (!observed.keySet().containsAll(ownedParts.keySet()) || !observed.keySet().containsAll(parts.keySet()))
            throw new IllegalArgumentException("Incomplete stage structure read set");
        Map<BlockPos, PlantStructurePlan.Change> changes = new LinkedHashMap<>();
        for (BlockPos pos : ownedParts.keySet().stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList()) {
            BlockState before = Objects.requireNonNull(observed.get(pos));
            Part target = parts.get(pos);
            if (target == null) {
                changes.put(pos, PlantStructurePlan.Change.removeOwned(before));
            } else {
                var policy = before.getFluidState().is(FluidTags.WATER)
                        ? PlantStructurePlan.Replacement.OWNED_WATER_PART : PlantStructurePlan.Replacement.OWNED_PART;
                changes.put(pos, new PlantStructurePlan.Change(before, target.state(), target.role(), policy));
            }
        }
        for (var entry : parts.entrySet()) {
            if (ownedParts.containsKey(entry.getKey())) continue;
            Part part = entry.getValue();
            changes.put(entry.getKey(), new PlantStructurePlan.Change(observed.get(entry.getKey()),
                    part.state(), part.role(), part.newPositionPolicy()));
        }
        return new PlantStructurePlan(expected, changes, observed);
    }
}
