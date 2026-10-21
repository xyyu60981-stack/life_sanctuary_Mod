package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;

/** Natural placement rules for wild common horsetail. */
public final class EquisetumNaturalCondition {
    public static final float CONFIRMATION_CHANCE = 0.30F;
    public static final float PREFERRED_COVER_FRACTION = 0.80F;
    public static final int PREFERRED_COVER_SEARCH_HEIGHT = 20;
    private EquisetumNaturalCondition() {}

    public static boolean isLegal(ServerLevel level, BlockPos base) {
        if (level.getFluidState(base).is(Fluids.WATER) || level.getFluidState(base.above()).is(Fluids.WATER)) return false;
        for (int y = 1; y <= 3; y++) {
            BlockPos space = base.above(y);
            if (!level.getBlockState(space).isAir()) return false;
        }
        return true;
    }

    /** Used by a future weighted sampler to prefer open air beneath a canopy. */
    public static boolean hasPreferredCover(ServerLevel level, BlockPos base) {
        for (int y = 4; y <= PREFERRED_COVER_SEARCH_HEIGHT; y++) {
            BlockPos cover = base.above(y);
            if (level.getBlockState(cover).isSolidRender(level, cover)
                    && level.canSeeSky(base.above(3))) {
                return true;
            }
        }
        return false;
    }
}
