package com.example.life_sanctuary;

import java.util.Map;
import net.minecraft.core.BlockPos;

/** Shared read-only validation. A visible receptacle never creates a missing harvest allowance. */
public final class MarchantiaState {
    private MarchantiaState() {}
    public enum Problem { NONE, IDENTITY, POPULATION, STRUCTURE, STAGE, QUOTA, MODE, ENVIRONMENT_UNKNOWN, TIMER }
    public static Problem check(PlantIndividualState plant, boolean reproductive, Map<BlockPos, String> parts) {
        if (plant == null || !plant.species().equals(MarchantiaRules.SPECIES)) return Problem.IDENTITY;
        if (!MarchantiaRules.validPopulation(plant)) return Problem.POPULATION;
        if (!parts.equals(Map.of(plant.base(), "liverwort"))) return Problem.STRUCTURE;
        if (!plant.stage().equals(MarchantiaRules.ADULT)) return Problem.STAGE;
        var quota = plant.harvests().get(MarchantiaCycles.QUOTA);
        if (quota == null || quota != (reproductive ? 1 : 0)) return Problem.QUOTA;
        int pending = plant.reproduction().getOrDefault(MarchantiaCycles.PENDING, 0);
        if (pending < 0 || pending > 1) return Problem.MODE;
        if (!plant.source().isStatic()) {
            var known = plant.reproduction().get(MarchantiaRules.KNOWN);
            if (known == null || known == 0) return Problem.ENVIRONMENT_UNKNOWN;
            if (known != 1) return Problem.MODE;
        }
        long target = plant.targetTicks();
        if (plant.source().isStatic() || reproductive || !plant.source().canExpand())
            return target == 0 ? Problem.NONE : Problem.TIMER;
        if (target == 0) return Problem.NONE;
        int minimum = pending == 1 ? MarchantiaCycles.MIN_CAPSULE : MarchantiaCycles.MIN_SPREAD;
        int maximum = pending == 1 ? MarchantiaCycles.MAX_CAPSULE : MarchantiaCycles.MAX_SPREAD;
        return target >= minimum && target <= maximum ? Problem.NONE : Problem.TIMER;
    }
}
