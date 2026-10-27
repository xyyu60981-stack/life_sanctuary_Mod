package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Entire fossils stay in the confirmed band; geometric planning never clips an authored structure. */
public final class CoalCoreRules {
    public static final int MINIMUM_Y = -10, MAXIMUM_Y = 10, MAXIMUM_PARTS = 512;
    private CoalCoreRules() {}
    public record AnchorRange(int minimum, int maximum) {
        public AnchorRange { if (minimum > maximum) throw new IllegalArgumentException("Impossible coal anchor height"); }
    }
    public static AnchorRange anchorRange(List<BlockPos> offsets, int worldMinimum, int worldMaximumExclusive) {
        if (worldMinimum >= worldMaximumExclusive) throw new IllegalArgumentException("Invalid world build interval");
        if (offsets.isEmpty() || offsets.size() > MAXIMUM_PARTS || new HashSet<>(offsets).size() != offsets.size())
            throw new IllegalArgumentException("Invalid coal structure footprint");
        int low = offsets.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int high = offsets.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        if (offsets.stream().anyMatch(p -> Math.abs((long) p.getX()) > 16 || Math.abs((long) p.getZ()) > 16 || Math.abs((long) p.getY()) > 20))
            throw new IllegalArgumentException("Unbounded coal structure offset");
        return new AnchorRange(Math.max(MINIMUM_Y, worldMinimum) - low, Math.min(MAXIMUM_Y, worldMaximumExclusive - 1) - high);
    }
    public static List<BlockPos> positions(BlockPos anchor, List<BlockPos> offsets, int quarterTurns) {
        anchorRange(offsets, MINIMUM_Y, MAXIMUM_Y + 1);
        if (quarterTurns < 0 || quarterTurns > 3) throw new IllegalArgumentException("Invalid quarter turn");
        var result = new ArrayList<BlockPos>();
        for (var offset : offsets) {
            int x = offset.getX(), z = offset.getZ();
            for (int i = 0; i < quarterTurns; i++) { int oldX = x; x = -z; z = oldX; }
            var pos = anchor.offset(x, offset.getY(), z);
            if (pos.getY() < MINIMUM_Y || pos.getY() > MAXIMUM_Y) throw new IllegalArgumentException("Coal structure crosses height band");
            result.add(pos);
        }
        return List.copyOf(result);
    }
    public interface Access<T> { T read(BlockPos pos); boolean write(BlockPos pos, T value); }
    public enum Result { APPLIED, STALE, ROLLED_BACK, RECOVERY_REQUIRED }
    /** Revert only our own writes. Never erase a different block installed by a third-party callback. */
    public static <T> Result apply(Map<BlockPos, T> expected, Map<BlockPos, T> replacements, Access<T> access) {
        if (expected.isEmpty() || expected.size() > MAXIMUM_PARTS || !expected.keySet().equals(replacements.keySet())
                || expected.keySet().stream().anyMatch(pos -> pos.getY() < MINIMUM_Y || pos.getY() > MAXIMUM_Y))
            throw new IllegalArgumentException("Invalid coal replacement batch");
        expected = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(expected));
        replacements = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(replacements));
        var attempted = new LinkedHashMap<BlockPos, T>();
        try {
            for (var pos : expected.keySet()) if (!Objects.equals(access.read(pos), expected.get(pos))) return Result.STALE;
            boolean okay = true;
            for (var pos : expected.keySet()) {
                if (!Objects.equals(access.read(pos), expected.get(pos))) { okay = false; break; }
                attempted.put(pos, replacements.get(pos));
                if (!access.write(pos, replacements.get(pos))) { okay = false; break; }
            }
            if (okay && replacements.entrySet().stream().allMatch(entry -> Objects.equals(access.read(entry.getKey()), entry.getValue()))) return Result.APPLIED;
        } catch (RuntimeException ignored) { /* Roll back the attempted subset below. */ }
        boolean recovered = true;
        for (var pos : new ArrayList<>(attempted.keySet()).reversed()) {
            try {
                var current = access.read(pos);
                if (Objects.equals(current, expected.get(pos))) continue;
                if (!Objects.equals(current, attempted.get(pos)) || !access.write(pos, expected.get(pos))
                        || !Objects.equals(access.read(pos), expected.get(pos))) recovered = false;
            } catch (RuntimeException error) { recovered = false; }
        }
        return recovered ? Result.ROLLED_BACK : Result.RECOVERY_REQUIRED;
    }
}
