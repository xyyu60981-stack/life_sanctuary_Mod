package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A fixed thin carpet; only its reproductive models receive pre-baked positional variation. */
public final class MarchantiaBlock extends CarpetBlock implements BonemealableBlock {
    public enum Surface implements StringRepresentable {
        SOIL, STONE;
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public static final MapCodec<MarchantiaBlock> CODEC = simpleCodec(MarchantiaBlock::new);
    public static final EnumProperty<Surface> SURFACE = EnumProperty.create("surface", Surface.class);
    public static final BooleanProperty REPRODUCTIVE = BooleanProperty.create("reproductive");
    public static final IntegerProperty FEMALE_COUNT = IntegerProperty.create("female_count", 1, 2);
    private static final VoxelShape CARPET = Block.box(0, 0, 0, 16, 1, 16), WITH_RECEPTACLES = Block.box(0, 0, 0, 16, 3.4, 16);
    public MarchantiaBlock(Properties properties) {
        super(properties); registerDefaultState(defaultBlockState().setValue(SURFACE, Surface.SOIL)
                .setValue(REPRODUCTIVE, false).setValue(FEMALE_COUNT, 1));
    }
    @Override public MapCodec<MarchantiaBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SURFACE, REPRODUCTIVE, FEMALE_COUNT);
    }
    public static Surface surface(BlockState support) { return support.is(MarchantiaRules.STONY) ? Surface.STONE : Surface.SOIL; }
    public static boolean support(BlockState state, BlockGetter level, BlockPos pos) {
        return (state.is(MarchantiaRules.SOIL) || state.is(MarchantiaRules.STONY)) && state.isFaceSturdy(level, pos, Direction.UP);
    }
    @Override public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) { return support(level.getBlockState(pos.below()), level, pos.below()); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(SURFACE, surface(context.getLevel().getBlockState(context.getClickedPos().below())));
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction != Direction.DOWN) return state;
        return support(neighbor, level, neighborPos) ? state.setValue(SURFACE, surface(neighbor)) : Blocks.AIR.defaultBlockState();
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(REPRODUCTIVE) ? WITH_RECEPTACLES : CARPET;
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return CARPET; }
    @Override public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) {
        if (state.getValue(REPRODUCTIVE)) return false;
        if (!(level instanceof ServerLevel server)) return true;
        var store = PlantIndividuals.get(server); var plant = store.atBase(pos).orElse(null);
        return MarchantiaCycles.healthy(plant) && !plant.source().isStatic() && MarchantiaCycles.owned(store, plant)
                && MarchantiaState.check(plant, false, store.partsOf(plant.id())) == MarchantiaState.Problem.NONE;
    }
    @Override public boolean isBonemealSuccess(Level level, net.minecraft.util.RandomSource random, BlockPos pos, BlockState state) {
        return isValidBonemealTarget(level, pos, state) && random.nextFloat() < 0.6F;
    }
    @Override public void performBonemeal(ServerLevel level, net.minecraft.util.RandomSource random, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos).equals(state) && isValidBonemealTarget(level, pos, state))
            PlantIndividuals.get(level).atBase(pos).ifPresent(plant -> MarchantiaCycles.makeCapsule(level, plant));
    }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (!state.getValue(REPRODUCTIVE) || !CollectorSporeHarvestAccess.canInteract(player, hand))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (player.isSpectator() || !level.mayInteract(player, pos) || !player.mayUseItemAt(pos, hit.getDirection(), stack))
            return net.minecraft.world.ItemInteractionResult.FAIL;
        if (level instanceof ServerLevel server && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            var result = MarchantiaCycles.harvest(server, pos, new CollectorSporeHarvestAccess(serverPlayer, hand));
            return result == MarchantiaCycles.Result.CHANGED ? net.minecraft.world.ItemInteractionResult.sidedSuccess(false)
                    : result == MarchantiaCycles.Result.RECOVERY_REQUIRED ? net.minecraft.world.ItemInteractionResult.FAIL
                    : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(true);
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var tool = params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL);
        if (tool == null) return List.of();
        boolean silk = tool.getEnchantmentLevel(params.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH)) > 0;
        return silk || tool.canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SHEARS_DIG)
                ? List.of(new ItemStack(LifeSanctuaryMod.MARCHANTIA_ITEM.get())) : List.of();
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        if (!old.is(this) && level instanceof ServerLevel server && server.getServer().isSameThread()
                && !PlantIndividuals.get(server).isApplyingStructure()) level.scheduleTick(pos, this, 1);
        super.onPlace(state, level, pos, old, moving);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) { MarchantiaRules.initializeStatic(level, pos); }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!next.is(this) && level instanceof ServerLevel server && server.getServer().isSameThread()) {
            var store = PlantIndividuals.get(server);
            if (!store.isReadOnly() && !store.isApplyingStructure()) store.atBase(pos).filter(p -> p.species().equals(MarchantiaRules.SPECIES))
                    .ifPresent(p -> store.remove(p.id(), p.base()));
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
