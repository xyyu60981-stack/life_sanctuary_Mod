package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** 阶段中新生采集/繁殖结构的首次额度；不是成熟重置或额度补充接口。 */
public record PlantStageResources(Map<String, Allocation> harvests, Map<String, Allocation> reproduction) {
    public static final PlantStageResources NONE = new PlantStageResources(Map.of(), Map.of());

    public record Allocation(BlockPos structure, String role, int count) {
        public Allocation {
            structure = structure.immutable();
            if (role == null || role.isBlank() || count < 0)
                throw new IllegalArgumentException("Invalid stage resource allocation");
        }
    }

    public PlantStageResources {
        harvests = Map.copyOf(harvests);
        reproduction = Map.copyOf(reproduction);
        validateKeys(harvests);
        validateKeys(reproduction);
    }

    private static void validateKeys(Map<String, Allocation> allocations) {
        var structures = new HashSet<BlockPos>();
        allocations.forEach((key, value) -> {
            if (key.isBlank() || !structures.add(value.structure()))
                throw new IllegalArgumentException("Empty key or duplicate resource structure");
        });
    }

    /** 仅构建快照，不修改存档；最终写入必须经过阶段结构提交。 */
    PlantIndividualState initialize(PlantIndividualState next, PlantStructurePlan plan, Map<BlockPos, String> owned) {
        var harvestBudgets = allocate(next.harvests(), harvests, plan, owned);
        var reproductionBudgets = allocate(next.reproduction(), reproduction, plan, owned);
        return new PlantIndividualState(next.id(), next.species(), next.base(), next.source(), next.stage(),
                next.population(), next.canBePruned(), next.pruned(), next.targetTicks(), next.elapsedTicks(),
                next.environmentReasons(), harvestBudgets, next.origin(), reproductionBudgets, next.runnerBudget());
    }

    private static Map<String, Integer> allocate(Map<String, Integer> previous, Map<String, Allocation> additions,
            PlantStructurePlan plan, Map<BlockPos, String> owned) {
        Map<String, Integer> result = new HashMap<>(previous);
        additions.forEach((key, allocation) -> {
            // 包括零额度在内的旧键都不能覆盖；结构移除后也不复用历史键。
            if (previous.containsKey(key)) throw new IllegalArgumentException("Resource key already initialized: " + key);
            var change = plan.changes().get(allocation.structure());
            if (change == null || !Objects.equals(change.role(), allocation.role())
                    || Objects.equals(owned.get(allocation.structure()), allocation.role()))
                throw new IllegalArgumentException("Resource requires a newly added structure or role");
            result.put(key, allocation.count());
        });
        return Map.copyOf(result);
    }
}
