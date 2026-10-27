package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/** 完整方块模型；每半柜保存两槽，配对只改变共同访问与供电，不搬移植物。 */
public final class CultivationCabinetBlock extends BaseEntityBlock {
    public static final MapCodec<CultivationCabinetBlock> CODEC = simpleCodec(CultivationCabinetBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<CultivationCabinetPairing.Part> PART =
            net.minecraft.world.level.block.state.properties.EnumProperty.create("part", CultivationCabinetPairing.Part.class);

    public CultivationCabinetBlock(Properties properties) {
        // 保持完整碰撞／静态模型，但不将启动信号经柜体传导给相邻漏斗。
        super(properties.pushReaction(PushReaction.BLOCK).isRedstoneConductor((state, world, pos) -> false));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, CultivationCabinetPairing.Part.SINGLE));
    }
    @Override public MapCodec<CultivationCabinetBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, PART); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }
    @Override protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        // 镜像改变左右手性：只翻朝向而不交换半柜标记，会让两个柜体互相指向外侧。
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING))).setValue(PART,
                mirror == net.minecraft.world.level.block.Mirror.NONE ? state.getValue(PART) : state.getValue(PART).opposite());
    }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        // 跳过柜体默认交互，但允许原版继续执行手持物品的放置／使用逻辑。
        if (player.isSecondaryUseActive()) return net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (!ReproductiveBagKind.SPORE.isFilledBag(stack))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level instanceof ServerLevel server) {
            var access = CultivationCabinetAccess.open(server, pos).orElse(null);
            boolean hasSpace = access != null && access.snapshot().slots().stream().anyMatch(java.util.Optional::isEmpty);
            if (hasSpace && player.containerMenu == player.inventoryMenu)
                CultivationCabinetInteraction.insertHeldBag(server, pos, player, hand, hit);
            else CultivationCabinetInteraction.open(server, pos, player, hit);
        }
        // 拒绝投入时也结束袋子的本次交互，避免继续执行袋子对地面的繁殖效果。
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (player.isSecondaryUseActive()) return net.minecraft.world.InteractionResult.PASS;
        if (level instanceof ServerLevel server) {
            if (player.getMainHandItem().isEmpty() && ReproductiveBagKind.SPORE.isFilledBag(player.getOffhandItem()))
                useItemOn(player.getOffhandItem(), state, level, pos, player, net.minecraft.world.InteractionHand.OFF_HAND, hit);
            else CultivationCabinetInteraction.open(server, pos, player, hit);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CultivationCabinetBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, LifeSanctuaryMod.CULTIVATION_BLOCK_ENTITY.get(),
                (world, pos, current, cabinet) -> {
                    if (world instanceof ServerLevel server) CultivationCabinetBlockEntity.serverTick(server, pos, current, cabinet);
                });
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
            BlockPos neighborPos, boolean moving) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CultivationCabinetBlockEntity cabinet)
            cabinet.invalidatePower();
        if (!level.isClientSide) {
            var facing = state.getValue(FACING);
            var part = state.getValue(PART);
            boolean relevant = part == CultivationCabinetPairing.Part.SINGLE
                    ? neighborPos.equals(pos.relative(facing.getClockWise())) || neighborPos.equals(pos.relative(facing.getCounterClockWise()))
                    : neighborPos.equals(pos.relative(part == CultivationCabinetPairing.Part.FIRST
                            ? facing.getClockWise() : facing.getCounterClockWise()));
            // 任意方向的变化仍使红石缓存失效；只有可能改变配对的位置才安排几何复核。
            if (relevant) level.scheduleTick(pos, this, 1);
        }
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!level.isClientSide && (!old.is(this) || old.getValue(FACING) != state.getValue(FACING)
                || old.getValue(PART) != state.getValue(PART))) level.scheduleTick(pos, this, 1);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        CultivationCabinetWorldConnection.reconcile(level, pos);
    }
    @Override protected boolean hasAnalogOutputSignal(BlockState state) { return true; }
    @Override protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level instanceof ServerLevel server ? CultivationCabinetWorldConnection.comparatorSignal(server, pos) : 0;
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CultivationCabinetBlockEntity cabinet)
            cabinet.restoreFromItem(stack);
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof CultivationCabinetBlockEntity cabinet)
            for (var stack : drops) if (stack.is(asItem())) cabinet.writeToItem(stack);
        return drops;
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()
                && level.getBlockEntity(pos) instanceof CultivationCabinetBlockEntity cabinet && cabinet.hasStoredContents()) {
            var stack = new ItemStack(this);
            cabinet.writeToItem(stack);
            var drop = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (!state.is(next.getBlock()) && !level.isClientSide) level.updateNeighbourForOutputSignal(pos, this);
    }
}
