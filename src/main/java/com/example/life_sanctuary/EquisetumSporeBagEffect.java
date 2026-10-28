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
        if (!PlantSporeTraits.species(speciesId).filter(PlantSpeciesRegistry.HORSETAIL.id()::equals).isPresent())
            return ReproductiveBagUseResult.rejected();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        BlockPos placement = target.above();
        if (level.isOutsideBuildHeight(target) || level.isOutsideBuildHeight(placement)
                || !level.getWorldBorder().isWithinBounds(placement)
                || !level.getChunkSource().hasChunk(target.getX() >> 4, target.getZ() >> 4))
            return ReproductiveBagUseResult.rejected();
        if (!level.getBlockState(target).is(LifeSanctuaryMod.HORSETAIL_SUBSTRATE)) {
            return ReproductiveBagUseResult.rejected();
        }
        var previous = level.getBlockState(placement);
        if (!previous.canBeReplaced() || !previous.getFluidState().isEmpty() || level.getBlockEntity(placement) != null)
            return ReproductiveBagUseResult.rejected();
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || store.atBase(placement).isPresent() || store.atPart(placement).isPresent()
                || HorsetailPopulations.get(level).member(placement) != null)
            return ReproductiveBagUseResult.rejected();
        var planted = LifeSanctuaryMod.SPHENOPHYTE_GAMETOPHYTE.get().defaultBlockState();
        if (!level.setBlock(placement, planted, Block.UPDATE_ALL)) return ReproductiveBagUseResult.rejected();
        if (!level.getBlockState(placement).equals(planted)
                || !level.getBlockState(target).is(LifeSanctuaryMod.HORSETAIL_SUBSTRATE)) {
            // Restore only our own write; a callback's replacement must remain untouched.
            if (level.getBlockState(placement).equals(planted)
                    && store.atBase(placement).isEmpty() && store.atPart(placement).isEmpty()
                    && HorsetailPopulations.get(level).member(placement) == null)
                level.setBlock(placement, previous, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            if (level.getBlockState(placement).equals(previous)) return ReproductiveBagUseResult.rejected();
            LifeSanctuaryMod.LOGGER.error("Horsetail sowing requires recovery at {}; bag not consumed", placement);
            return ReproductiveBagUseResult.recovery();
        }
        try {
            HorsetailPopulations.get(level).markPlayerPlaced(level, placement);
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Cannot register planted horsetail at " + placement + "; bag not consumed", failure);
            return ReproductiveBagUseResult.recovery();
        }
        return ReproductiveBagUseResult.consumed(false);
    }
}
