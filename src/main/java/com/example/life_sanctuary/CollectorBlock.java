package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class CollectorBlock extends BaseEntityBlock {
    public static final MapCodec<CollectorBlock> CODEC = simpleCodec(CollectorBlock::new);
    public CollectorBlock(Properties properties) { super(properties); }
    @Override public MapCodec<CollectorBlock> codec() { return CODEC; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CollectorBlockEntity(pos, state);
    }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected boolean hasAnalogOutputSignal(BlockState state) { return true; }

    @Override protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        // Use vanilla fullness rules (including per-item stack limits), not occupied-slot count.
        // Evaluated on comparator demand only; no collector tick is registered.
        return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (!state.is(next.getBlock()) && !level.isClientSide)
            level.updateNeighbourForOutputSignal(pos, this);
        // Do not scatter inventory: the dropped collector already carries its contents.
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                        Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CollectorBlockEntity collector) {
            player.openMenu(collector, buffer -> { buffer.writeBlockPos(pos); buffer.writeVarInt(collector.getContainerSize() / 9); });
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    // Contents travel in the loot item's CONTAINER component; never scatter them in onRemove.
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()
                && level.getBlockEntity(pos) instanceof CollectorBlockEntity collector && (!collector.isEmpty() || collector.hasFilterConfiguration())) {
            ItemStack stack = new ItemStack(this);
            stack.applyComponents(collector.collectComponents());
            ItemEntity drop = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
