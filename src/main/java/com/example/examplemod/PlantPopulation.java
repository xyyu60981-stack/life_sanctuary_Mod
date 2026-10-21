package com.example.examplemod;

import net.minecraft.core.BlockPos;

/** A fixed-coordinate population region for one species. */
public final class PlantPopulation {
    public static final int DEFAULT_HORIZONTAL_SIZE = 16;
    public static final int VERTICAL_MARGIN = 5;

    private final String speciesId;
    private final PlantPopulationTag tag;
    private final int horizontalSize;
    private final int minimumX;
    private final int minimumZ;
    private final int minimumBaseY;
    private final int maximumBaseY;
    private final int maximumIndividuals;
    private int individuals;
    private int expansionIndividuals;

    public PlantPopulation(String speciesId, int horizontalSize, int minimumX, int minimumZ, int minimumBaseY, int maximumBaseY, int maximumIndividuals) {
        this.speciesId = speciesId;
        if (horizontalSize != 16 && horizontalSize != 32) throw new IllegalArgumentException("Population size must be 16 or 32");
        if ((minimumX % horizontalSize) != 0 || (minimumZ % horizontalSize) != 0) throw new IllegalArgumentException("Population boundary must be aligned");
        this.horizontalSize = horizontalSize;
        this.minimumX = minimumX;
        this.minimumZ = minimumZ;
        this.minimumBaseY = minimumBaseY;
        this.maximumBaseY = maximumBaseY;
        this.maximumIndividuals = maximumIndividuals;
        this.tag = new PlantPopulationTag(speciesId, minimumX, minimumZ, horizontalSize);
    }

    public static PlantPopulation anchoredAt(String speciesId, BlockPos base, int horizontalSize, int minimumBaseY, int maximumBaseY, int maximumIndividuals) {
        int minimumX = base.getX() & ~(horizontalSize - 1);
        int minimumZ = base.getZ() & ~(horizontalSize - 1);
        return new PlantPopulation(speciesId, horizontalSize, minimumX, minimumZ, minimumBaseY, maximumBaseY, maximumIndividuals);
    }

    public boolean containsBase(BlockPos base) {
        return base.getX() >= minimumX && base.getX() < minimumX + horizontalSize
                && base.getZ() >= minimumZ && base.getZ() < minimumZ + horizontalSize
                && base.getY() >= minimumBaseY && base.getY() <= maximumBaseY;
    }

    public BlockPos centerAnchor() {
        return new BlockPos(minimumX + horizontalSize / 2, (minimumBaseY + maximumBaseY) / 2, minimumZ + horizontalSize / 2);
    }

    /** Total population membership, including manually planted individuals. */
    public boolean canAddIndividual() { return individuals < maximumIndividuals; }
    /** Whether an automatic spread attempt may add another expansion member. */
    public boolean canAutoExpand() { return individuals < maximumIndividuals; }
    public String speciesId() { return speciesId; }
    public PlantPopulationTag tag() { return tag; }
    public int individuals() { return individuals; }
    public void addIndividual() { if (canAddIndividual()) individuals++; }
    public void addIndividual(PlantOrigin origin) {
        if (origin == PlantOrigin.PLAYER_CULTIVATED || origin == PlantOrigin.ONE_TIME_CULTIVATION) {
            individuals++;
        } else if (canAutoExpand()) {
            individuals++;
            expansionIndividuals++;
        }
    }
    public void recordAutomaticSpread() { if (canAutoExpand()) { individuals++; expansionIndividuals++; } }
    public int expansionIndividuals() { return expansionIndividuals; }
    public int minimumBaseY() { return minimumBaseY; }
    public int maximumBaseY() { return maximumBaseY; }
    public int maximumIndividuals() { return maximumIndividuals; }
    public int horizontalSize() { return horizontalSize; }
    public int minimumX() { return minimumX; }
    public int minimumZ() { return minimumZ; }
}
