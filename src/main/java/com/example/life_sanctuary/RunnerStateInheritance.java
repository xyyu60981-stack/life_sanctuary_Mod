package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Pure inheritance planning; never writes world blocks or creates population members. */
public final class RunnerStateInheritance {
    private RunnerStateInheritance() {}

    public record Timer(long target, long elapsed) {
        public Timer {
            if (target < 0 || elapsed < 0 || elapsed > target) throw new IllegalArgumentException("Invalid timer");
        }
        public long remaining() { return target - elapsed; }
    }

    public record Part(PlantRunnerNetwork network, Map<String, Integer> harvests, Timer timer,
            Map<String, Integer> reproduction) {
        public Part(PlantRunnerNetwork network, Map<String, Integer> harvests, Timer timer) {
            this(network, harvests, timer, Map.of());
        }
        public Part {
            java.util.Objects.requireNonNull(network);
            java.util.Objects.requireNonNull(timer);
            harvests = mergeHarvests(harvests, Map.of());
            reproduction = mergeHarvests(reproduction, Map.of());
        }
    }

    public record CutResult(List<Part> parts, Map<String, Integer> removedHarvests,
            Map<String, Integer> removedReproduction) {
        public CutResult(List<Part> parts, Map<String, Integer> removedHarvests) {
            this(parts, removedHarvests, Map.of());
        }
        public CutResult {
            parts = List.copyOf(parts);
            removedHarvests = Map.copyOf(removedHarvests);
            removedReproduction = Map.copyOf(removedReproduction);
        }
        public boolean reallySplit() { return parts.size() > 1; }
    }

    /**
     * Each saved quota must identify its physical structure anchor in the original runner.
     * Exhausted zero quotas are retained. Removed structures are reported separately, never inherited.
     * For off-runner leaves use their attachment segment as anchor; world-part removal remains adapter work.
     */
    public static CutResult cut(PlantRunnerNetwork original, BlockPos removed,
            Map<String, Integer> harvests, Map<String, BlockPos> anchors, Timer timer) {
        return cut(original, removed, harvests, anchors, Map.of(), Map.of(), timer);
    }

    /** Both resource domains share one connectivity pass, but never share or replenish quotas. */
    public static CutResult cut(PlantRunnerNetwork original, BlockPos removed,
            Map<String, Integer> harvests, Map<String, BlockPos> harvestAnchors,
            Map<String, Integer> reproduction, Map<String, BlockPos> reproductionAnchors, Timer timer) {
        java.util.Objects.requireNonNull(timer);
        validateAnchors(original, harvests, harvestAnchors);
        validateAnchors(original, reproduction, reproductionAnchors);
        var networks = original.remove(removed);
        Map<BlockPos, Integer> componentByPosition = new HashMap<>();
        for (int i = 0; i < networks.size(); i++) {
            int component = i;
            networks.get(i).positions().forEach(pos -> componentByPosition.put(pos, component));
        }
        Allocation collected = allocate(harvests, harvestAnchors, componentByPosition, networks.size());
        Allocation reproduced = allocate(reproduction, reproductionAnchors, componentByPosition, networks.size());
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < networks.size(); i++)
            parts.add(new Part(networks.get(i), collected.parts().get(i), timer, reproduced.parts().get(i)));
        return new CutResult(parts, collected.removed(), reproduced.removed());
    }

    private static void validateAnchors(PlantRunnerNetwork original, Map<String, Integer> resources,
            Map<String, BlockPos> anchors) {
        if (!resources.keySet().equals(anchors.keySet()))
            throw new IllegalArgumentException("Every resource needs exactly one anchor");
        resources.forEach((key, quota) -> {
            if (key == null || key.isBlank() || quota == null || quota < 0
                    || anchors.get(key) == null || !original.positions().contains(anchors.get(key)))
                throw new IllegalArgumentException("Invalid resource anchor or quota");
        });
    }

    private record Allocation(List<Map<String, Integer>> parts, Map<String, Integer> removed) {}

    private static Allocation allocate(Map<String, Integer> resources, Map<String, BlockPos> anchors,
            Map<BlockPos, Integer> componentByPosition, int componentCount) {
        List<Map<String, Integer>> allocations = new ArrayList<>();
        for (int i = 0; i < componentCount; i++) allocations.add(new HashMap<>());
        Map<String, Integer> removedResources = new HashMap<>();
        resources.forEach((key, quota) -> {
            Integer component = componentByPosition.get(anchors.get(key));
            if (component == null) removedResources.put(key, quota);
            else allocations.get(component).put(key, quota);
        });
        return new Allocation(allocations, removedResources);
    }

    /** Geometry and resource proposal only; source/stage compatibility is checked by replaceGroup. */
    public static java.util.Optional<Part> merge(Part first, Part second) {
        return first.network().merge(second.network()).map(network -> new Part(network,
                mergeHarvests(first.harvests(), second.harvests()), mergeTimer(first.timer(), second.timer()),
                mergeHarvests(first.reproduction(), second.reproduction())));
    }

    /**
     * Same kind of timer only: merge keeps the longer remaining duration, never sums or shortens it.
     * Retains enough target duration to represent that remainder without a new random roll.
     */
    public static Timer mergeTimer(Timer first, Timer second) {
        long target = Math.max(first.target(), second.target());
        long remaining = Math.max(first.remaining(), second.remaining());
        return new Timer(target, target - remaining);
    }

    /** Resource keys must be stable per structure, not per-individual labels such as just "spores". */
    public static Map<String, Integer> mergeHarvests(Map<String, Integer> first, Map<String, Integer> second) {
        Map<String, Integer> merged = new HashMap<>();
        for (var source : List.of(first, second)) {
            source.forEach((key, quota) -> {
                if (key == null || key.isBlank() || quota == null || quota < 0)
                    throw new IllegalArgumentException("Invalid quota");
                if (merged.putIfAbsent(key, quota) != null)
                    throw new IllegalArgumentException("Ambiguous shared resource key: " + key);
            });
        }
        return Map.copyOf(merged);
    }
}
