package com.example.examplemod;

import net.minecraft.core.BlockPos;

/** Resolves the base block used for survival, population membership and density. */
public final class PlantBasePosition {
    private PlantBasePosition() {}

    /** For a one-block plant, its own position is its base. */
    public static BlockPos singleBlock(BlockPos position) {
        return position;
    }

    /** For a footprint, the southeast (positive X and positive Z) cell is the base. */
    public static BlockPos southeastCell(BlockPos footprintOrigin, int width, int depth) {
        return footprintOrigin.offset(Math.max(0, width - 1), 0, Math.max(0, depth - 1));
    }
}
