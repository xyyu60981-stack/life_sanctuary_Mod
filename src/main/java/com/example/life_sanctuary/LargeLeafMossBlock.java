package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Density is visual block state; all one-to-three stems share exactly one individual identity. */
public final class LargeLeafMossBlock extends BushBlock implements BonemealableBlock {
    public static final MapCodec<LargeLeafMossBlock> CODEC = simpleCodec(LargeLeafMossBlock::new);
    public static final IntegerProperty COUNT = IntegerProperty.create("count", 1, 3);
    public static final BooleanProperty CURLED = BooleanProperty.create("curled"), CULTIVATED = BooleanProperty.create("cultivated");
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 9, 14);
    public LargeLeafMossBlock(Properties properties) { super(properties); registerDefaultState(defaultBlockState().setValue(COUNT, 1).setValue(CURLED, false).setValue(CULTIVATED, false)); }
    @Override public MapCodec<LargeLeafMossBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(COUNT, CURLED, CULTIVATED); }
    @Override protected boolean mayPlaceOn(BlockState state, BlockGetter world, BlockPos pos) { return state.is(BlockTags.DIRT); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) { return SHAPE; }
    public static boolean dry(LevelReader world, BlockPos pos) { return world.getBiome(pos).is(LifeSanctuaryMod.DRY_BIOMES); }
    @Override protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return !context.isSecondaryUseActive() && context.getItemInHand().is(asItem()) && state.getValue(COUNT) < 3 || super.canBeReplaced(state, context);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var old = context.getLevel().getBlockState(context.getClickedPos());
        return old.is(this) ? old.setValue(COUNT, Math.min(3, old.getValue(COUNT) + 1)) : defaultBlockState().setValue(CULTIVATED, true)
                .setValue(CURLED, dry(context.getLevel(), context.getClickedPos()));
    }
    @Override protected void onPlace(BlockState state, Level world, BlockPos pos, BlockState old, boolean moving) {
        if (!world.isClientSide && !old.is(this)) world.scheduleTick(pos, this, 1);
    }
    @Override public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity player, ItemStack stack) {
        if (world instanceof ServerLevel server) LargeLeafMossGrowth.get(server).refresh(server, pos);
    }
    @Override protected void tick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random) { LargeLeafMossGrowth.get(world).refresh(world, pos); }
    @Override protected void onRemove(BlockState state, Level world, BlockPos pos, BlockState next, boolean moving) {
        if (!next.is(this) && world instanceof ServerLevel server && !PlantIndividuals.get(server).isApplyingStructure()) LargeLeafMossGrowth.get(server).remove(server, pos);
        super.onRemove(state, world, pos, next, moving);
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) { return List.of(new ItemStack(this, state.getValue(COUNT))); }
    private static boolean acceptsGrowth(LevelReader world, BlockPos pos) {
        if (dry(world, pos)) return false;
        if (!(world instanceof ServerLevel server)) return true;
        var store = PlantIndividuals.get(server);
        return !store.isReadOnly() && !store.isApplyingStructure() && store.atBase(pos).map(plant -> !plant.pruned()).orElse(true);
    }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack tool, BlockState state, Level world, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (!tool.canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SHEARS_DIG))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!player.mayBuild() || player.isSpectator()) return net.minecraft.world.ItemInteractionResult.FAIL;
        if (world instanceof ServerLevel server) {
            if (PlantIndividuals.get(server).isReadOnly() || PlantIndividuals.get(server).isApplyingStructure()) return net.minecraft.world.ItemInteractionResult.FAIL;
            LargeLeafMossGrowth.get(server).refresh(server, pos);
            var store = PlantIndividuals.get(server); var plant = store.atBase(pos).orElse(null);
            if (plant == null || PlantLifecycle.prune(store, plant.id()) != PlantLifecycle.Result.CHANGED)
                return net.minecraft.world.ItemInteractionResult.CONSUME;
            tool.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
            world.playSound(null, pos, net.minecraft.sounds.SoundEvents.SHEEP_SHEAR, net.minecraft.sounds.SoundSource.BLOCKS, 1, 1);
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(world.isClientSide);
    }
    @Override public boolean isValidBonemealTarget(LevelReader world, BlockPos pos, BlockState state) { return acceptsGrowth(world, pos); }
    @Override public boolean isBonemealSuccess(Level world, RandomSource random, BlockPos pos, BlockState state) { return acceptsGrowth(world, pos); }
    @Override public void performBonemeal(ServerLevel world, RandomSource random, BlockPos pos, BlockState state) {
        if (!world.getBlockState(pos).equals(state) || !acceptsGrowth(world, pos)) return;
        if (state.getValue(COUNT) == 3) popResource(world, pos, new ItemStack(this));
        else world.setBlock(pos, state.setValue(COUNT, state.getValue(COUNT) + 1), Block.UPDATE_ALL);
        LargeLeafMossGrowth.get(world).refresh(world, pos);
    }
}
