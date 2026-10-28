package com.example.life_sanctuary;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Block item adapter that records the source assigned to a placed individual. */
public final class HorsetailPlantItem extends BlockItem {
    private final PlantSource source;

    public HorsetailPlantItem(Block block, Properties properties) {
        this(block, properties, PlantSource.PLAYER);
    }

    public HorsetailPlantItem(Block block, Properties properties, PlantSource source) {
        super(block, properties);
        this.source = source;
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        if (getBlock() != LifeSanctuaryMod.EQUISETUM_ARVENSE.get()) return super.placeBlock(context, state);
        var level = context.getLevel();
        var upperPos = context.getClickedPos().above();
        if (!EquisetumArvenseBlock.canFitAdult(level, context.getClickedPos())) return false;
        BlockState previousUpper = level.getBlockState(upperPos);
        BlockState lower = state.setValue(EquisetumArvenseBlock.PART, DoubleBlockHalf.LOWER)
                .setValue(EquisetumArvenseBlock.GROWING, source.canExpand());
        BlockState upper = lower.setValue(EquisetumArvenseBlock.PART, DoubleBlockHalf.UPPER);
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        if (!level.setBlock(upperPos, upper, quiet)) return false;
        if (!super.placeBlock(context, lower)) {
            if (level.getBlockState(upperPos).equals(upper)) level.setBlock(upperPos, previousUpper, quiet);
            return false;
        }
        level.updateNeighborsAt(upperPos, getBlock());
        return true;
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        InteractionResult result = super.place(context);
        if (result.consumesAction() && context.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            var pos = context.getClickedPos();
            if (level.getBlockState(pos).is(getBlock())) {
                HorsetailPopulations populations = HorsetailPopulations.get(level);
                if (source == PlantSource.ONE_TIME) populations.markOneTimePlaced(level, pos);
                else populations.markPlayerPlaced(level, pos);
                if (getBlock() == LifeSanctuaryMod.EQUISETUM_ARVENSE.get()) level.scheduleTick(pos, getBlock(), 1);
            }
        }
        return result;
    }
}
