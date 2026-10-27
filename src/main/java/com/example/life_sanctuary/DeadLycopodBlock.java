package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.common.ItemAbilities;

/** One item / three baked model states. Material registration supplies the actual substrate tag. */
public class DeadLycopodBlock extends Block {
    public static final EnumProperty<DeadLycopodStackRules.Part> PART =
            EnumProperty.create("part", DeadLycopodStackRules.Part.class);
    public static final MapCodec<DeadLycopodBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(),
            TagKey.codec(Registries.BLOCK).fieldOf("substrates").forGetter(block -> block.substrates)
    ).apply(instance, DeadLycopodBlock::new));
    private final TagKey<Block> substrates;

    public DeadLycopodBlock(Properties properties, TagKey<Block> substrates) {
        super(properties.pushReaction(PushReaction.DESTROY));
        this.substrates = java.util.Objects.requireNonNull(substrates);
        registerDefaultState(defaultBlockState().setValue(PART, DeadLycopodStackRules.Part.SINGLE));
    }
    @Override public MapCodec<? extends DeadLycopodBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }
    private DeadLycopodStackRules.Part part(BlockState state) { return state.is(this) ? state.getValue(PART) : null; }
    private boolean substrate(BlockState state) { return !state.is(this) && state.is(substrates); }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var below = context.getLevel().getBlockState(context.getClickedPos().below());
        return DeadLycopodStackRules.placement(part(below), substrate(below))
                .map(value -> defaultBlockState().setValue(PART, value)).orElse(null);
    }
    @Override public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        var below = level.getBlockState(pos.below());
        var value = state.getValue(PART);
        return DeadLycopodStackRules.update(value, part(below), null, substrate(below)).isPresent();
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction.getAxis() != Direction.Axis.Y) return state;
        var below = direction == Direction.DOWN ? neighbor : level.getBlockState(pos.below());
        var above = direction == Direction.UP ? neighbor : level.getBlockState(pos.above());
        return DeadLycopodStackRules.update(state.getValue(PART), part(below), part(above), substrate(below))
                .map(value -> state.setValue(PART, value)).orElse(Blocks.AIR.defaultBlockState());
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var tool = params.getOptionalParameter(LootContextParams.TOOL);
        if (DeadLycopodStackRules.dropCount(tool != null && tool.canPerformAction(ItemAbilities.SHEARS_DIG)) == 0)
            return List.of();
        var drop = new ItemStack(this);
        return drop.isEmpty() ? List.of() : List.of(drop);
    }
}
