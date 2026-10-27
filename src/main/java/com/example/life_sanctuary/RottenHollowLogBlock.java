package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/** Baked hollow-log model: eggs are world state only, never carried by the decoration item. */
public class RottenHollowLogBlock extends RotatedPillarBlock implements SimpleWaterloggedBlock {
    public static final BooleanProperty EGGS = BooleanProperty.create("eggs");
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final MapCodec<RottenHollowLogBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(), BuiltInRegistries.ITEM.byNameCodec().fieldOf("stalk").forGetter(block -> block.stalk)
    ).apply(instance, RottenHollowLogBlock::new));
    private final Item stalk;

    public RottenHollowLogBlock(Properties properties, Item stalk) {
        super(properties);
        this.stalk = java.util.Objects.requireNonNull(stalk);
        if (stalk == net.minecraft.world.item.Items.AIR) throw new IllegalArgumentException("Hollow logs require a real stalk item");
        registerDefaultState(defaultBlockState().setValue(EGGS, false).setValue(WATERLOGGED, false));
    }
    @Override public MapCodec<? extends RottenHollowLogBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(EGGS, WATERLOGGED);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return super.getStateForPlacement(context).setValue(EGGS, false)
                .setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).is(FluidTags.WATER));
    }
    @Override protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }
    public boolean hasWetEggs(BlockState state) {
        return state.is(this) && state.getValue(EGGS) && state.getValue(WATERLOGGED);
    }
    private void synchronize(LevelAccessor level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            HollowLogEggSoaking.get(server).synchronize(server, pos, this);
        }
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState before, boolean moving) {
        super.onPlace(state, level, pos, before, moving); synchronize(level, pos);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel server) HollowLogEggSoaking.get(server).remove(pos);
        super.onRemove(state, level, pos, next, moving);
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }
    @Override public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluid) {
        boolean placed = SimpleWaterloggedBlock.super.placeLiquid(level, pos, state, fluid);
        if (placed) synchronize(level, pos);
        return placed;
    }
    @Override public ItemStack pickupBlock(net.minecraft.world.entity.player.Player player,
            LevelAccessor level, BlockPos pos, BlockState state) {
        var result = SimpleWaterloggedBlock.super.pickupBlock(player, level, pos, state);
        if (!result.isEmpty()) synchronize(level, pos);
        return result;
    }
    private static boolean silk(ServerLevel level, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var enchantment = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        return stack.getEnchantmentLevel(enchantment) > 0;
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (silk(params.getLevel(), params.getOptionalParameter(LootContextParams.TOOL))) {
            var decoration = new ItemStack(this); // No block state / egg components on the returned item.
            return decoration.isEmpty() ? List.of() : List.of(decoration);
        }
        var result = new ItemStack(stalk, HollowLogEggRules.stalkCount(params.getLevel().random));
        return result.isEmpty() ? List.of() : List.of(result);
    }
    @Override protected void spawnAfterBreak(BlockState state, ServerLevel level, BlockPos pos, ItemStack tool, boolean experience) {
        super.spawnAfterBreak(state, level, pos, tool, experience);
        int count = HollowLogEggRules.silverfishCount(state.getValue(EGGS), silk(level, tool),
                level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS), level.random);
        for (int i = 0; i < count; i++) {
            var fish = EntityType.SILVERFISH.create(level);
            if (fish != null) {
                fish.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360, 0);
                if (level.addFreshEntity(fish)) fish.spawnAnim();
            }
        }
    }
}
