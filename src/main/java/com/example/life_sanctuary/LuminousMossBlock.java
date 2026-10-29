package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Three baked appearances share one block ID. Capsule cross is one reproductive structure. */
public final class LuminousMossBlock extends BushBlock implements EnvironmentSensitivePlant, net.minecraft.world.level.block.BonemealableBlock {
    public static final MapCodec<LuminousMossBlock> CODEC = simpleCodec(LuminousMossBlock::new);
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, 2);
    private static final VoxelShape[] SHAPES = {Block.box(0, 0, 0, 16, 7, 16),
            Block.box(0, 0, 0, 16, 13, 16), Block.box(0, 0, 0, 16, 15, 16)};
    public LuminousMossBlock(Properties properties) { super(properties); registerDefaultState(defaultBlockState().setValue(AGE, 0)); }
    @Override public MapCodec<LuminousMossBlock> codec() { return CODEC; }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        if (!old.is(this) && level instanceof ServerLevel server && server.getServer().isSameThread()
                && !PlantIndividuals.get(server).isApplyingStructure())
            level.scheduleTick(pos, this, 1);
        super.onPlace(state, level, pos, old, moving);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        LuminousMossRules.initializeStatic(level, pos);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(AGE); }
    @Override protected boolean mayPlaceOn(BlockState support, BlockGetter level, BlockPos pos) {
        return support.is(LuminousMossRules.SUBSTRATES) && support.isFaceSturdy(level, pos, Direction.UP);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(AGE)];
    }
    @Override public boolean isEnvironmentSuitable(LevelReader level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel server) {
            var plant = PlantIndividuals.get(server).atBase(pos).orElse(null);
            return plant != null && !plant.environmentReasons().contains("cold");
        }
        return !PlantEnvironment.isCold(level, pos);
    }
    @Override public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) {
        if (state.getValue(AGE) != 1) return false;
        if (!(level instanceof ServerLevel server)) return true;
        var store = PlantIndividuals.get(server); var plant = store.atBase(pos).orElse(null);
        return LuminousMossCycles.healthy(plant) && !plant.source().isStatic() && LuminousMossCycles.owned(store, plant)
                && LuminousMossState.check(plant, state.getValue(AGE), store.partsOf(plant.id())) == LuminousMossState.Problem.NONE
                && plant.harvests().getOrDefault(LuminousMossCycles.QUOTA, 0) == 0;
    }
    @Override public boolean isBonemealSuccess(Level level, net.minecraft.util.RandomSource random, BlockPos pos, BlockState state) {
        return isValidBonemealTarget(level, pos, state) && random.nextFloat() < 0.6F;
    }
    @Override public void performBonemeal(ServerLevel level, net.minecraft.util.RandomSource random, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos).equals(state) && isValidBonemealTarget(level, pos, state))
            PlantIndividuals.get(level).atBase(pos).ifPresent(plant -> LuminousMossCycles.makeCapsule(level, plant));
    }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (state.getValue(AGE) != 2 || !CollectorSporeHarvestAccess.canInteract(player, hand))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (player.isSpectator() || !level.mayInteract(player, pos) || !player.mayUseItemAt(pos, hit.getDirection(), stack))
            return net.minecraft.world.ItemInteractionResult.FAIL;
        if (level instanceof ServerLevel server && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            var result = LuminousMossCycles.harvest(server, pos, new CollectorSporeHarvestAccess(serverPlayer, hand));
            return result == LuminousMossCycles.Result.CHANGED ? net.minecraft.world.ItemInteractionResult.sidedSuccess(false)
                    : result == LuminousMossCycles.Result.RECOVERY_REQUIRED ? net.minecraft.world.ItemInteractionResult.FAIL
                    : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(true);
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var tool = params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL);
        if (tool == null) return List.of();
        boolean shears = tool.canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SHEARS_DIG);
        boolean silk = tool.getEnchantmentLevel(params.getLevel().registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH)) > 0;
        return shears || silk ? List.of(new ItemStack(LifeSanctuaryMod.LUMINOUS_MOSS_ITEM.get())) : List.of();
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!next.is(this) && level instanceof ServerLevel server && server.getServer().isSameThread()) {
            var store = PlantIndividuals.get(server);
            if (!store.isReadOnly() && !store.isApplyingStructure()) store.atBase(pos)
                    .filter(plant -> plant.species().equals(LuminousMossRules.SPECIES))
                    .ifPresent(plant -> store.remove(plant.id(), plant.base()));
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
