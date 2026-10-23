package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 自然芦木种群一次成型的不可变元数据计划；不以成长 tick 逐株补齐数量。 */
public record RhizomeNaturalPopulationPlan(Map<UUID, PlantIndividualState> plants,
        Map<UUID, PlantRhizomeSystem> systems, RhizomePopulation.Stages stages,
        RhizomePopulation.NaturalTargets targets) {
    public RhizomeNaturalPopulationPlan {
        plants = Map.copyOf(plants);
        systems = Map.copyOf(systems);
        if (plants.isEmpty() || systems.isEmpty() || plants.size() != targets.total())
            throw new IllegalArgumentException("Incomplete natural rhizome population");
        var first = plants.values().iterator().next();
        var definition = PlantSpeciesRegistry.find(first.species()).orElseThrow();
        stages.validate(definition);
        if (!RhizomePopulation.findStages(first.species()).filter(stages::equals).isPresent())
            throw new IllegalArgumentException("Natural stage totals require the registered rhizome population profile");
        if (!definition.population().map(population -> population.horizontalSize() == 32).orElse(false))
            throw new IllegalArgumentException("Natural rhizome population requires fixed 32x32 bounds");
        Map<PlantPopulationCounts.Group, Integer> counts = new HashMap<>();
        Map<UUID, UUID> identities = new HashMap<>();
        var bases = new java.util.HashSet<net.minecraft.core.BlockPos>();
        Map<net.minecraft.core.BlockPos, UUID> physical = new HashMap<>();
        Map<net.minecraft.core.BlockPos, UUID> roots = new HashMap<>();
        for (var entry : systems.entrySet()) {
            var system = entry.getValue();
            if (!entry.getKey().equals(system.id()) || system.source() != PlantSource.STATIC
                    || !system.species().equals(first.species()) || !system.population().equals(first.population())
                    || system.regionX() != Math.floorDiv(first.base().getX(), 32)
                    || system.regionZ() != Math.floorDiv(first.base().getZ(), 32)
                    || system.rhizomes().isEmpty() || !plants.containsKey(system.id()))
                throw new IllegalArgumentException("Natural population requires complete static underground systems");
            if (identities.putIfAbsent(system.id(), system.id()) != null)
                throw new IllegalArgumentException("Natural root also belongs to another individual");
            var primary = plants.get(system.id());
            if (primary.base().getX() != system.origin().getX() || primary.base().getZ() != system.origin().getZ())
                throw new IllegalArgumentException("Natural root must belong to its primary above-ground plant");
            for (var root : system.roots())
                if (roots.putIfAbsent(root, system.id()) != null) throw new IllegalArgumentException("Overlapping natural roots");
            var nodes = new java.util.HashSet<>(system.rhizomes());
            nodes.addAll(system.risingRhizomes());
            for (var pos : nodes)
                if (physical.putIfAbsent(pos, system.id()) != null) throw new IllegalArgumentException("Overlapping natural rhizomes");
            for (var shoot : system.shoots().entrySet()) {
                var plant = plants.get(shoot.getKey());
                if (plant == null || !plant.base().equals(shoot.getValue().base())
                        || identities.putIfAbsent(shoot.getKey(), system.id()) != null)
                    throw new IllegalArgumentException("Conflicting natural plant identity");
            }
        }
        for (var entry : plants.entrySet()) {
            var plant = entry.getValue();
            if (!entry.getKey().equals(plant.id()) || plant.source() != PlantSource.STATIC || plant.origin() != PlantOrigin.NATURAL
                    || !plant.species().equals(first.species()) || !plant.population().equals(first.population())
                    || plant.population().isBlank() || !identities.containsKey(plant.id())
                    || plant.targetTicks() != 0 || plant.elapsedTicks() != 0 || !bases.add(plant.base())
                    || Math.floorDiv(plant.base().getX(), 32) != Math.floorDiv(first.base().getX(), 32)
                    || Math.floorDiv(plant.base().getZ(), 32) != Math.floorDiv(first.base().getZ(), 32))
                throw new IllegalArgumentException("Natural plant outside shared population or identity");
            counts.merge(new PlantPopulationCounts.Group(plant.source(), plant.stage(), plant.origin()), 1, Math::addExact);
            if (physical.containsKey(plant.base())) throw new IllegalArgumentException("Plant base overlaps underground node");
        }
        for (var entry : roots.entrySet())
            if (physical.containsKey(entry.getKey()) && !physical.get(entry.getKey()).equals(entry.getValue()))
                throw new IllegalArgumentException("Natural root overlaps another individual's rhizome");
        var actual = RhizomePopulation.count(new PlantPopulationCounts(counts), stages);
        if (actual.unclassified() != 0 || actual.adult() != targets.adult()
                || actual.youngClusters() != targets.youngClusters() || actual.subadult() != targets.subadult())
            throw new IllegalArgumentException("Natural population does not match its once-selected stage totals");
    }
}
