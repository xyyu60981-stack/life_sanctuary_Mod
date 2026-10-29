package com.example.life_sanctuary;

import java.util.Map;
import net.minecraft.core.BlockPos;

/** Pure consistency checks shared by active callbacks and read-only tooltips. Never repairs quotas. */
public final class LuminousMossState {
    private LuminousMossState() {}
    public enum Problem { NONE, IDENTITY, POPULATION, STRUCTURE, STAGE, QUOTA, MODE, ENVIRONMENT_UNKNOWN, TIMER }
    public static Problem check(PlantIndividualState plant, int age, Map<BlockPos, String> parts) {
        if (plant == null || !plant.species().equals(LuminousMossRules.SPECIES)) return Problem.IDENTITY;
        if (!plant.population().equals(LuminousMossRules.population(plant.base()))) return Problem.POPULATION;
        if (!parts.equals(Map.of(plant.base(), "moss"))) return Problem.STRUCTURE;
        if (age < 0 || age > 2 || !plant.stage().equals(age == 0 ? LuminousMossRules.SEEDLING : LuminousMossRules.ADULT))
            return Problem.STAGE;
        // Missing is not an initialized zero: never mint a new quota from the visible model.
        var quota = plant.harvests().get(LuminousMossCycles.QUOTA);
        if (quota == null || quota != (age == 2 ? 1 : 0)) return Problem.QUOTA;
        int pending = plant.reproduction().getOrDefault(LuminousMossCycles.PENDING, 0);
        if (pending < 0 || pending > 1) return Problem.MODE;
        if (!plant.source().isStatic()) {
            var dry = plant.reproduction().get(LuminousMossCycles.DRY);
            if (dry == null) return Problem.ENVIRONMENT_UNKNOWN;
            if (dry < 0 || dry > 1) return Problem.MODE;
        }
        long target = plant.targetTicks();
        if (plant.source().isStatic() || age == 2 || age != 0 && !plant.source().canExpand())
            return target == 0 ? Problem.NONE : Problem.TIMER;
        if (age == 0) return target >= LuminousMossRules.MIN_GROWTH && target <= LuminousMossRules.MAX_GROWTH
                ? Problem.NONE : Problem.TIMER;
        // An idle adult is safe (e.g. a completed stage saved before its next cycle was scheduled).
        if (target == 0) return Problem.NONE;
        int minimum = pending == 1 ? LuminousMossCycles.MIN_CAPSULE : LuminousMossCycles.MIN_SPREAD;
        int maximum = pending == 1 ? LuminousMossCycles.MAX_CAPSULE : LuminousMossCycles.MAX_SPREAD;
        return target >= minimum && target <= maximum ? Problem.NONE : Problem.TIMER;
    }
}
