package com.example.life_sanctuary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Immutable horizontal runner topology. Used only when structure changes, never scanned each tick.
 * Touching cardinal neighbors connect; diagonal contact does not. No world reads or resource changes.
 */
public final class PlantRunnerNetwork {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private final Set<BlockPos> positions;
    private final int maximumDegree;

    public PlantRunnerNetwork(Set<BlockPos> positions, int maximumDegree) {
        if (maximumDegree != 2 && maximumDegree != 3)
            throw new IllegalArgumentException("Runner degree must be 2 (unbranched) or 3 (branched)");
        if (positions.isEmpty()) throw new IllegalArgumentException("Empty runner");
        Set<BlockPos> copied = new HashSet<>();
        positions.forEach(pos -> copied.add(pos.immutable()));
        this.positions = Set.copyOf(copied);
        this.maximumDegree = maximumDegree;
        int y = copied.iterator().next().getY();
        int doubledEdges = 0;
        for (BlockPos pos : copied) {
            if (pos.getY() != y) throw new IllegalArgumentException("Runner cannot climb");
            int degree = neighbors(pos, copied).size();
            if (degree > maximumDegree) throw new IllegalArgumentException("Too many runner connections");
            doubledEdges += degree;
        }
        if (components(copied).size() != 1) throw new IllegalArgumentException("Disconnected runner");
        if (doubledEdges / 2 != copied.size() - 1) throw new IllegalArgumentException("Runner loop");
    }

    public Set<BlockPos> positions() { return positions; }
    public int maximumDegree() { return maximumDegree; }

    /** A single starting segment is also an endpoint. */
    public Set<BlockPos> endpoints() {
        Set<BlockPos> result = new HashSet<>();
        for (BlockPos pos : positions)
            if (neighbors(pos, positions).size() <= 1) result.add(pos);
        return Set.copyOf(result);
    }

    /** Does not imply that the species has unused length/branching quota or a legal substrate. */
    public Optional<PlantRunnerNetwork> add(BlockPos position) {
        if (positions.contains(position)) return Optional.empty();
        Set<BlockPos> expanded = new HashSet<>(positions);
        expanded.add(position.immutable());
        return validated(expanded, maximumDegree);
    }

    /**
     * Cutting a tip yields one component; only two or more components represent a true split.
     * Removing the final segment yields an empty list. Ordering is stable for save/migration adapters.
     */
    public List<PlantRunnerNetwork> remove(BlockPos position) {
        if (!positions.contains(position)) return List.of(this);
        Set<BlockPos> remaining = new HashSet<>(positions);
        remaining.remove(position);
        return components(remaining).stream().map(component -> new PlantRunnerNetwork(component, maximumDegree)).toList();
    }

    /** Merge topology only. The caller must validate species, ownership, source and quota inheritance. */
    public Optional<PlantRunnerNetwork> merge(PlantRunnerNetwork other) {
        if (maximumDegree != other.maximumDegree || !java.util.Collections.disjoint(positions, other.positions))
            return Optional.empty();
        Set<BlockPos> joined = new HashSet<>(positions);
        joined.addAll(other.positions);
        return validated(joined, maximumDegree);
    }

    private static Optional<PlantRunnerNetwork> validated(Set<BlockPos> positions, int maximumDegree) {
        try {
            return Optional.of(new PlantRunnerNetwork(positions, maximumDegree));
        } catch (IllegalArgumentException rejected) {
            return Optional.empty();
        }
    }

    private static List<BlockPos> neighbors(BlockPos pos, Set<BlockPos> positions) {
        List<BlockPos> neighbors = new ArrayList<>(4);
        for (Direction direction : HORIZONTAL) {
            BlockPos next = pos.relative(direction);
            if (positions.contains(next)) neighbors.add(next);
        }
        return neighbors;
    }

    private static List<Set<BlockPos>> components(Set<BlockPos> positions) {
        List<Set<BlockPos>> result = new ArrayList<>();
        Set<BlockPos> unseen = new HashSet<>(positions);
        // Sorting once avoids choosing a different split order from hash iteration after reload.
        List<BlockPos> ordered = positions.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList();
        for (BlockPos start : ordered) {
            if (!unseen.remove(start)) continue;
            Set<BlockPos> component = new HashSet<>();
            ArrayDeque<BlockPos> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                BlockPos current = pending.removeFirst();
                component.add(current);
                for (BlockPos neighbor : neighbors(current, positions))
                    if (unseen.remove(neighbor)) pending.addLast(neighbor);
            }
            result.add(Set.copyOf(component));
        }
        return result;
    }
}
