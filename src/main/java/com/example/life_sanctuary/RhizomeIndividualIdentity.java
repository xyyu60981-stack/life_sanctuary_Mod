package com.example.life_sanctuary;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 同源克隆共享生物个体 ID；各地上植株的记录 ID 仅作为结构/计时句柄，不作为新的生物个体。 */
public final class RhizomeIndividualIdentity {
    private RhizomeIndividualIdentity() {}
    public record Identity(UUID individualId, UUID plantRecordId, String population, boolean linkedShoot) {}

    public static Optional<Identity> resolve(ServerLevel level, PlantIndividualState plant) {
        var systems = PlantRhizomeSystems.get(level);
        var individuals = PlantIndividuals.get(level);
        if (systems.isReadOnly() || individuals.isReadOnly()
                || !individuals.find(plant.id()).filter(plant::equals).isPresent()) return Optional.empty();
        var owner = systems.forShoot(plant.id()).orElse(null);
        boolean linked = owner != null;
        if (owner == null) owner = systems.find(plant.id()).orElse(null);
        if (owner == null) return plant.origin() == PlantOrigin.PLAYER_CULTIVATED
                && RhizomePopulation.findStages(plant.species()).isPresent()
                ? Optional.of(new Identity(plant.id(), plant.id(), plant.population(), false)) : Optional.empty();
        if (!owner.species().equals(plant.species()) || !owner.population().equals(plant.population())
                || !owner.containsRegion(plant.base())) return Optional.empty();
        if (linked && !owner.shoots().get(plant.id()).base().equals(plant.base())) return Optional.empty();
        return Optional.of(new Identity(owner.id(), plant.id(), owner.population(), linked));
    }

    public static boolean sameIndividual(ServerLevel level, PlantIndividualState first, PlantIndividualState second) {
        var a = resolve(level, first);
        var b = resolve(level, second);
        return a.isPresent() && b.isPresent() && a.get().individualId().equals(b.get().individualId());
    }
}
