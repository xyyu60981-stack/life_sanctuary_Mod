package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;

/** Stable label for a player's species population region. */
public record PlantPopulationTag(String speciesId, int minimumX, int minimumZ, int horizontalSize) {
    public static PlantPopulationTag fromBase(String speciesId, BlockPos base, int horizontalSize) {
        int minX = base.getX() & ~(horizontalSize - 1);
        int minZ = base.getZ() & ~(horizontalSize - 1);
        return new PlantPopulationTag(speciesId, minX, minZ, horizontalSize);
    }

    public String serializedName() {
        return "life_sanctuary:population/" + speciesId + "/" + minimumX + "_" + minimumZ;
    }
}
