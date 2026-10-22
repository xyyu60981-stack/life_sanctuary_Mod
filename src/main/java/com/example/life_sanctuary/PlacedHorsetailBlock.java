package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Cut stems are decorations, with no population membership, scheduled ticks or harvest quota. */
public class PlacedHorsetailBlock extends SupportedHorsetailBlock {
    public static final IntegerProperty STEMS = IntegerProperty.create("nutritional_stems", 0, 3);

    public PlacedHorsetailBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(STEMS, 1)
                .setValue(EquisetumArvenseBlock.SPORE_STEM, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STEMS, EquisetumArvenseBlock.SPORE_STEM);
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return context.getItemInHand().getItem() instanceof CutHorsetailItem item && item.canAddTo(state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return HorsetailStemInteraction.shape(state);
    }

    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(BlockState state,
            net.minecraft.world.phys.HitResult target, net.minecraft.world.level.LevelReader level,
            BlockPos pos, net.minecraft.world.entity.player.Player player) {
        return HorsetailStemInteraction.pickStack(state, pos, target);
    }

    @Override
    protected void onRemove(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
            BlockState next, boolean movedByPiston) {
        // Also clears stale standalone-spore records left by older versions.
        if (!state.is(next.getBlock()) && level instanceof net.minecraft.server.level.ServerLevel server) {
            HorsetailPopulations.get(server).remove(pos);
            PlantWaterMonitoring.get(server).remove(pos);
        }
        super.onRemove(state, level, pos, next, movedByPiston);
    }
}
