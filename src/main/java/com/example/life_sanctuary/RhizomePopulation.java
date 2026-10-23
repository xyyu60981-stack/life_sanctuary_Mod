package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.util.RandomSource;

/** 芦木种群按实际地上植株计数，同一个体的多个地上植株不能合并为一次计数。 */
public final class RhizomePopulation {
    private RhizomePopulation() {}
    private static final java.util.Map<net.minecraft.resources.ResourceLocation, Stages> RULES = new java.util.concurrent.ConcurrentHashMap<>();
    public static void register(net.minecraft.resources.ResourceLocation species, Stages stages) {
        var definition = PlantSpeciesRegistry.find(species).orElseThrow();
        stages.validate(definition);
        if (!definition.population().map(population -> population.horizontalSize() == 32).orElse(false))
            throw new IllegalArgumentException("Rhizome species requires a 32x32 population");
        if (RULES.putIfAbsent(species, stages) != null) throw new IllegalArgumentException("Duplicate rhizome population rules");
    }
    public static java.util.Optional<Stages> findStages(net.minecraft.resources.ResourceLocation species) {
        return java.util.Optional.ofNullable(RULES.get(species));
    }
    public record Stages(Set<String> adult, Set<String> youngClusters, Set<String> subadult) {
        public Stages {
            adult = Set.copyOf(adult);
            youngClusters = Set.copyOf(youngClusters);
            subadult = Set.copyOf(subadult);
            Set<String> all = new HashSet<>();
            for (var group : java.util.List.of(adult, youngClusters, subadult)) {
                if (group.isEmpty()) throw new IllegalArgumentException("Explicit population stages required");
                for (String stage : group)
                    if (stage.isBlank() || !all.add(stage)) throw new IllegalArgumentException("Overlapping population stages");
            }
        }
        public void validate(PlantSpeciesDefinition species) {
            for (var group : java.util.List.of(adult, youngClusters, subadult))
                if (!species.stages().keySet().containsAll(group)) throw new IllegalArgumentException("Unknown population stage");
        }
    }
    public record Counts(int adult, int youngClusters, int subadult, int unclassified) {
        public Counts {
            if (adult < 0 || youngClusters < 0 || subadult < 0 || unclassified < 0)
                throw new IllegalArgumentException("Negative population count");
        }
        public int total() { return Math.addExact(Math.addExact(adult, youngClusters), Math.addExact(subadult, unclassified)); }
    }
    public record NaturalTargets(int adult, int youngClusters, int subadult) {
        public NaturalTargets {
            if (adult < 1 || adult > 8 || youngClusters < 1 || youngClusters > 6 || subadult < 1 || subadult > 6)
                throw new IllegalArgumentException("Natural calamite population outside configured ranges");
        }
        public int total() { return adult + youngClusters + subadult; }
    }

    /** 在一次性自然生成尝试中只抽一次；不将这些初始数量范围自动当成人工生长硬上限。 */
    public static NaturalTargets naturalTargets(RandomSource random) {
        return new NaturalTargets(1 + random.nextInt(8), 1 + random.nextInt(6), 1 + random.nextInt(6));
    }

    /** 使用已维护的种群索引；包含所有来源，不按系统 ID 去重、不排除人工植株。 */
    public static Counts count(PlantPopulationCounts population, Stages stages) {
        int adult = 0, clusters = 0, subadult = 0, unknown = 0;
        for (var entry : population.groups().entrySet()) {
            String stage = entry.getKey().stage();
            int amount = entry.getValue();
            if (stages.adult().contains(stage)) adult = Math.addExact(adult, amount);
            else if (stages.youngClusters().contains(stage)) clusters = Math.addExact(clusters, amount);
            else if (stages.subadult().contains(stage)) subadult = Math.addExact(subadult, amount);
            else unknown = Math.addExact(unknown, amount);
        }
        return new Counts(adult, clusters, subadult, unknown);
    }
}
