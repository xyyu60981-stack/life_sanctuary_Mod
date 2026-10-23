package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/** 物种阶段已实际提交后调用；共享个体中每株只在自己的 D 阶段追加自己的长度。 */
public final class RhizomeStageGrowth {
    private RhizomeStageGrowth() {}
    public enum Reason { ADDED, ALREADY_STARTED, NO_C_BUDGET, NO_SYSTEM, INVALID_STAGE, PAUSED, STALE, READ_ONLY, RECOVERY_REQUIRED }
    public record Result(Reason reason, Optional<PlantRhizomeSystem> system) {}

    public static Result beginD(ServerLevel level, PlantIndividualState plant, String dStage, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var individuals = PlantIndividuals.get(level);
        var systems = PlantRhizomeSystems.get(level);
        if (individuals.isReadOnly() || systems.isReadOnly()) return result(Reason.READ_ONLY);
        if (individuals.isApplyingStructure() || systems.isApplyingStructure()) return result(Reason.PAUSED);
        if (!individuals.find(plant.id()).filter(plant::equals).isPresent()) return result(Reason.STALE);
        var definition = PlantSpeciesRegistry.find(plant.species()).orElse(null);
        if (definition == null || !definition.stages().containsKey(dStage) || !plant.stage().equals(dStage)) return result(Reason.INVALID_STAGE);
        if (!plant.source().canExpand() || plant.pruned() || !plant.environmentReasons().isEmpty()) return result(Reason.PAUSED);
        var system = systems.forShoot(plant.id()).orElseGet(() -> systems.find(plant.id()).orElse(null));
        if (system == null) return result(Reason.NO_SYSTEM);
        if (systems.needsRecovery(system.id())) return result(Reason.RECOVERY_REQUIRED);
        if (!system.species().equals(plant.species()) || !system.population().equals(plant.population())
                || !system.containsRegion(plant.base())) return result(Reason.STALE);
        if (system.growth().isEmpty()) return result(Reason.NO_C_BUDGET);
        var budget = plant.id().equals(system.id()) ? system.growth().get()
                : Optional.ofNullable(system.growth().get().branches().get(plant.id())).map(RhizomeGrowthState.Branch::growth).orElse(null);
        if (budget == null) return result(Reason.NO_C_BUDGET);
        if (budget.dStarted()) return new Result(Reason.ALREADY_STARTED, Optional.of(system));
        var next = system.beginD(plant.id(), java.util.Objects.requireNonNull(random)).orElseThrow();
        return systems.replace(system, next) ? new Result(Reason.ADDED, Optional.of(next)) : result(Reason.STALE);
    }

    private static Result result(Reason reason) { return new Result(reason, Optional.empty()); }
}
