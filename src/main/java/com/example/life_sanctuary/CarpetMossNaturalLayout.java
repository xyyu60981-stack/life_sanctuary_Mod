package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** Bounded, read-only layout planning. Caller supplies already validated surface positions. */
public final class CarpetMossNaturalLayout {
    private CarpetMossNaturalLayout() {}
    private static final int ATTEMPTS = 32;
    public record Limits(int minimum, int maximum, int minimumGroups, int maximumGroups) {
        public Limits {
            if (minimum < 1 || maximum < minimum || maximum > 256 || minimumGroups < 1
                    || maximumGroups < minimumGroups || maximumGroups > minimum)
                throw new IllegalArgumentException("Invalid natural carpet patch limits");
        }
    }
    public record Layout(List<List<BlockPos>> clusters) {
        public Layout { clusters = clusters.stream().map(List::copyOf).toList(); }
        public List<BlockPos> positions() { return clusters.stream().flatMap(List::stream).toList(); }
    }
    public static boolean neighbors(BlockPos first, BlockPos second) {
        return Math.abs((long) first.getX() - second.getX()) + Math.abs((long) first.getZ() - second.getZ()) == 1
                && Math.abs((long) first.getY() - second.getY()) <= 1;
    }
    /** Empty means this attempt found no complete layout; never returns an undersized patch. */
    public static Layout plan(Collection<BlockPos> surfaces, RandomSource random, Limits limits) {
        if (surfaces.size() > 256) throw new IllegalArgumentException("At most one surface per regional column");
        var candidates = surfaces.stream().map(BlockPos::immutable).sorted(java.util.Comparator
                .comparingInt((BlockPos p) -> p.getX()).thenComparingInt(p -> p.getZ()).thenComparingInt(p -> p.getY())).toList();
        var columns = new HashSet<Long>();
        String region = null;
        for (var pos : candidates) {
            String current = (pos.getX() >> 4) + "/" + (pos.getZ() >> 4);
            if (region != null && !region.equals(current)) throw new IllegalArgumentException("Layout crosses its 16x16 region");
            region = current;
            long column = ((long) pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL);
            if (!columns.add(column)) throw new IllegalArgumentException("Duplicate surface column");
        }
        if (candidates.size() < limits.minimum()) return new Layout(List.of());
        var adjacent = new LinkedHashMap<BlockPos, List<BlockPos>>();
        var available = new HashSet<>(candidates);
        for (var pos : candidates) {
            var neighbors = new ArrayList<BlockPos>();
            for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) {
                var neighbor = pos.relative(direction).above(dy);
                if (available.contains(neighbor)) neighbors.add(neighbor);
            }
            adjacent.put(pos, List.copyOf(neighbors));
        }
        int count = limits.minimum() + random.nextInt(Math.min(limits.maximum(), candidates.size())
                - limits.minimum() + 1);
        int groups = limits.minimumGroups() + random.nextInt(limits.maximumGroups()
                - limits.minimumGroups() + 1);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var owners = new LinkedHashMap<BlockPos, Integer>();
            var clusters = new ArrayList<List<BlockPos>>();
            for (int group = 0; group < groups; group++) {
                var seeds = candidates.stream().filter(p -> !owners.containsKey(p)
                        && adjacent.get(p).stream().noneMatch(owners::containsKey)).toList();
                if (seeds.isEmpty()) break;
                var seed = seeds.get(random.nextInt(seeds.size()));
                owners.put(seed, group); clusters.add(new ArrayList<>(List.of(seed)));
            }
            if (clusters.size() != groups) continue;
            while (owners.size() < count) {
                var frontier = new ArrayList<Map.Entry<BlockPos, Integer>>();
                for (var pos : candidates) {
                    if (owners.containsKey(pos)) continue;
                    Set<Integer> touching = new HashSet<>();
                    for (var neighbor : adjacent.get(pos)) if (owners.containsKey(neighbor)) touching.add(owners.get(neighbor));
                    if (touching.size() == 1) frontier.add(Map.entry(pos, touching.iterator().next()));
                }
                if (frontier.isEmpty()) break;
                var next = frontier.get(random.nextInt(frontier.size()));
                owners.put(next.getKey(), next.getValue()); clusters.get(next.getValue()).add(next.getKey());
            }
            if (owners.size() == count) return new Layout(clusters);
        }
        return new Layout(List.of());
    }
}
