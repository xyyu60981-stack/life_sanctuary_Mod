package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;

/** Bounded one-shot bank checks. No world mutations, searches for new chunks, or runtime scans. */
public final class LavaLakeRemnantRules {
    private LavaLakeRemnantRules() {}
    public record Limits(int horizontalRadius, int verticalRadius, int lavaRadius, int minimumLavaSources, int attempts, int maximumPlacements) {
        public Limits {
            if (horizontalRadius < 1 || horizontalRadius > 16 || verticalRadius < 0 || verticalRadius > 8
                    || lavaRadius < 1 || lavaRadius > 4 || minimumLavaSources < 1
                    || minimumLavaSources > 2 * (2 * lavaRadius + 1) * (2 * lavaRadius + 1) - 2
                    || attempts < 1 || attempts > 16 || maximumPlacements < 1 || maximumPlacements > 4 || maximumPlacements > attempts)
                throw new IllegalArgumentException("Invalid bounded lava lake remnant settings");
        }
    }
    public record Cell(boolean loaded, boolean air, boolean dry, boolean blockEntity, boolean support, boolean lavaSource) {
        public static final Cell UNKNOWN = new Cell(false, false, false, false, false, false);
    }
    public interface Snapshot {
        boolean allowedBiome(BlockPos pos);
        Cell cell(BlockPos pos);
    }
    public enum Result { READY, WRONG_BIOME, OCCUPIED, NO_SUPPORT, NO_LAVA, UNKNOWN }
    public static Result inspect(BlockPos pos, Limits limits, Snapshot snapshot) {
        if (!snapshot.allowedBiome(pos)) return Result.WRONG_BIOME;
        var target = snapshot.cell(pos);
        var below = snapshot.cell(pos.below());
        if (!target.loaded() || !below.loaded()) return Result.UNKNOWN;
        if (!target.air() || !target.dry() || target.blockEntity()) return Result.OCCUPIED;
        if (!below.support() || below.blockEntity() || !below.dry()) return Result.NO_SUPPORT;
        int sources = 0; boolean unknown = false;
        for (int y = -1; y <= 0; y++) for (int x = -limits.lavaRadius(); x <= limits.lavaRadius(); x++)
            for (int z = -limits.lavaRadius(); z <= limits.lavaRadius(); z++) {
                var cell = snapshot.cell(pos.offset(x, y, z));
                if (!cell.loaded()) unknown = true;
                else if (cell.lavaSource() && ++sources >= limits.minimumLavaSources()) return Result.READY;
            }
        return unknown ? Result.UNKNOWN : Result.NO_LAVA;
    }
}
