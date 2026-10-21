package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/** Player cultivation effect for the equisetum arvense spore bag. */
public final class EquisetumSporeBagEffect implements SporeBagEffect {
    public static final EquisetumSporeBagEffect INSTANCE = new EquisetumSporeBagEffect();

    private EquisetumSporeBagEffect() {}

    @Override
    public ReproductiveBagUseResult use(ServerLevel level, BlockPos target, String speciesId) {
        if (!"equisetum_arvense".equals(speciesId)) return ReproductiveBagUseResult.rejected();
        if (!level.getBlockState(target).is(LifeSanctuaryMod.HORSETAIL_SUBSTRATE)) {
            return ReproductiveBagUseResult.rejected();
        }
        BlockPos placement = target.above();
        if (!level.getBlockState(placement).canBeReplaced()) return ReproductiveBagUseResult.rejected();
        level.setBlock(placement, LifeSanctuaryMod.EQUISETUM_ARVENSE_GAMETOPHYTE.get().defaultBlockState(), Block.UPDATE_ALL);
        HorsetailPopulations.get(level).markPlayerPlaced(level, placement);
        return ReproductiveBagUseResult.consumed(false);
    }
}
