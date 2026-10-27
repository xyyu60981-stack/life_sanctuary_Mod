package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Full transparent cube. The facing is the exhibit's viewing face, including floor and ceiling. */
public final class DisplayCabinetBlock extends BaseEntityBlock {
    public static final MapCodec<DisplayCabinetBlock> CODEC = simpleCodec(DisplayCabinetBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public DisplayCabinetBlock(Properties properties) { super(properties); registerDefaultState(defaultBlockState().setValue(FACING, Direction.UP)); }
    @Override public MapCodec<DisplayCabinetBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getClickedFace()); }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.setValue(FACING, mirror.mirror(state.getValue(FACING))); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DisplayCabinetBlockEntity(pos, state); }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!player.mayBuild() || player.isSpectator()) return ItemInteractionResult.FAIL;
        if (!(level.getBlockEntity(pos) instanceof DisplayCabinetBlockEntity cabinet)) return ItemInteractionResult.FAIL;
        if (!cabinet.exhibit().isEmpty()) return ItemInteractionResult.SUCCESS;
        if (!level.isClientSide && cabinet.insert(stack, player.getAbilities().instabuild))
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM, net.minecraft.sounds.SoundSource.BLOCKS, 1, 1);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown() || !player.mayBuild() || player.isSpectator()) return InteractionResult.PASS;
        if (!(level.getBlockEntity(pos) instanceof DisplayCabinetBlockEntity cabinet) || cabinet.exhibit().isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide) {
            var item = cabinet.take();
            if (!item.isEmpty()) {
                player.getInventory().add(item);
                if (!item.isEmpty()) player.drop(item, false);
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM, net.minecraft.sounds.SoundSource.BLOCKS, 1, 1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof DisplayCabinetBlockEntity cabinet) {
            var exhibit = cabinet.take();
            if (!exhibit.isEmpty()) popResource(level, pos, exhibit);
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
