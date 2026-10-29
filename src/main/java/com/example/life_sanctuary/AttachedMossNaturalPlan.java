package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

/** Pure worldgen preparation: callers validate terrain; planning never reads a world or SavedData. */
public final class AttachedMossNaturalPlan {
    private AttachedMossNaturalPlan() {}
    public record Candidate(BlockPos pos, Direction backing, boolean preferred) {
        public Candidate {
            pos = pos.immutable();
            if (!backing.getAxis().isHorizontal()) throw new IllegalArgumentException("Only side attachment is allowed");
        }
    }
    /** A seed-dependent parity grid leaves a whole chunk between eligible population chunks. */
    public static boolean eligibleChunk(int chunkX, int chunkZ, long worldSeed) {
        long phase = mix(worldSeed);
        return Math.floorMod(chunkX, 2) == (int) (phase & 1)
                && Math.floorMod(chunkZ, 2) == (int) ((phase >>> 1) & 1);
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
    private static boolean inside(BlockPos pos, int chunkX, int chunkZ) {
        return (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ;
    }
    /**
     * Candidate support must already be complete, exposed and legal. Preferred means the supplied
     * terrain check found a log/stone/cobblestone side within the requested ground-height band.
     * This helper does not invent a biome list, generation frequency or a ground-height detector.
     */
    public static Map<BlockPos, Direction> plan(int chunkX, int chunkZ, long worldSeed,
            Collection<Candidate> supplied, RandomSource random) {
        if (supplied.size() > 16 * 16 * 4 * (AttachedMossRules.PREFERRED_HEIGHT + 1))
            throw new IllegalArgumentException("Too many side candidates in one chunk");
        var candidates = supplied.stream().sorted(Comparator.comparingInt((Candidate c) -> c.pos().getX())
                .thenComparingInt(c -> c.pos().getZ()).thenComparingInt(c -> c.pos().getY())
                .thenComparingInt(c -> c.backing().ordinal())).toList();
        var faces = new HashSet<Map.Entry<BlockPos, Direction>>();
        for (var candidate : candidates) {
            if (!inside(candidate.pos(), chunkX, chunkZ) || !inside(candidate.pos().relative(candidate.backing()), chunkX, chunkZ))
                throw new IllegalArgumentException("Candidate or support crosses the generation chunk");
            if (!faces.add(Map.entry(candidate.pos(), candidate.backing()))) throw new IllegalArgumentException("Duplicate candidate face");
        }
        if (!eligibleChunk(chunkX, chunkZ, worldSeed) || candidates.isEmpty()) return Map.of();
        var preferred = candidates.stream().filter(Candidate::preferred).toList();
        var seeds = preferred.isEmpty() ? candidates : preferred;
        var seed = seeds.get(random.nextInt(seeds.size()));
        var selected = new LinkedHashMap<BlockPos, Direction>();
        selected.put(seed.pos(), seed.backing());
        var nearby = new ArrayList<>(candidates.stream().filter(c -> c.pos().distManhattan(seed.pos()) <= 3).toList());
        int target = AttachedMossRules.NATURAL_MIN + random.nextInt(AttachedMossRules.NATURAL_MAX - AttachedMossRules.NATURAL_MIN + 1);
        while (selected.size() < target) {
            var available = nearby.stream().filter(c -> !selected.containsKey(c.pos())).toList();
            if (available.isEmpty()) break;
            var favored = available.stream().filter(Candidate::preferred).toList();
            var pool = favored.isEmpty() ? available : favored;
            var next = pool.get(random.nextInt(pool.size()));
            selected.put(next.pos(), next.backing());
        }
        return java.util.Collections.unmodifiableMap(selected);
    }
}
