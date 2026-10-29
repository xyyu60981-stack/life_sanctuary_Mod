package com.example.life_sanctuary;

import java.util.Map;
import net.minecraft.core.BlockPos;

/** Shared read-only validation. A visible receptacle never creates a missing harvest allowance. */
public final class StarMossState {
    private StarMossState() {}
    public enum Problem { NONE, IDENTITY, POPULATION, STRUCTURE, STAGE, QUOTA, MODE, TIMER }
    public static Problem check(PlantIndividualState plant, boolean reproductive, Map<BlockPos, String> parts) {
        if (plant == null || !plant.species().equals(StarMossRules.SPECIES)) return Problem.IDENTITY;
        if (!StarMossRules.validPopulation(plant)) return Problem.POPULATION;
        if (!parts.equals(Map.of(plant.base(), "star_moss"))) return Problem.STRUCTURE;
        if (!plant.stage().equals(StarMossRules.ADULT)) return Problem.STAGE;
        var quota = plant.harvests().get(StarMossCycles.QUOTA);
        if (quota == null || quota != (reproductive ? 1 : 0)) return Problem.QUOTA;
        int pending = plant.reproduction().getOrDefault(StarMossCycles.PENDING, 0);
        if (pending < 0 || pending > 1) return Problem.MODE;
        long target = plant.targetTicks();
        if (plant.source().isStatic() || reproductive || !plant.source().canExpand())
            return target == 0 ? Problem.NONE : Problem.TIMER;
        if (target == 0) return Problem.NONE;
        int minimum = pending == 1 ? StarMossCycles.MIN_CAPSULE : StarMossCycles.MIN_SPREAD;
        int maximum = pending == 1 ? StarMossCycles.MAX_CAPSULE : StarMossCycles.MAX_SPREAD;
        return target >= minimum && target <= maximum ? Problem.NONE : Problem.TIMER;
    }
}
