package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Direct side attachment only. No inherited vine random ticking, ceiling face or hanging chains. */
public final class AttachedMossBlock extends Block implements BonemealableBlock {
    public static final MapCodec<AttachedMossBlock> CODEC = simpleCodec(AttachedMossBlock::new);
    public static final BooleanProperty NORTH = BooleanProperty.create("north"), EAST = BooleanProperty.create("east"),
            SOUTH = BooleanProperty.create("south"), WEST = BooleanProperty.create("west");
    static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final VoxelShape[] SHAPES = new VoxelShape[16];
    static {
        var faces = new VoxelShape[]{box(0, 0, 0, 16, 16, 1), box(15, 0, 0, 16, 16, 16),
                box(0, 0, 15, 16, 16, 16), box(0, 0, 0, 1, 16, 16)};
        for (int mask = 0; mask < 16; mask++) {
            var shape = Shapes.empty();
            for (int side = 0; side < 4; side++) if ((mask & (1 << side)) != 0) shape = Shapes.or(shape, faces[side]);
            SHAPES[mask] = shape;
        }
    }
    public AttachedMossBlock(Properties properties) {
        super(properties); registerDefaultState(defaultBlockState().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false));
    }
    @Override public MapCodec<AttachedMossBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(NORTH, EAST, SOUTH, WEST); }
    public static BooleanProperty property(Direction side) {
        return switch (side) { case NORTH -> NORTH; case EAST -> EAST; case SOUTH -> SOUTH; case WEST -> WEST;
            default -> throw new IllegalArgumentException("Only horizontal moss faces are supported"); };
    }
    public static int mask(BlockState state) {
        int mask = 0; for (int i = 0; i < SIDES.size(); i++) if (state.getValue(property(SIDES.get(i)))) mask |= 1 << i; return mask;
    }
    private static boolean loaded(LevelReader level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && (!(level instanceof ServerLevel server)
                || server.getWorldBorder().isWithinBounds(pos) && server.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4));
    }
    private static boolean completeSupport(BlockState support, BlockGetter level, BlockPos pos, Direction face) {
        return support.isCollisionShapeFullBlock(level, pos) && support.isFaceSturdy(level, pos, face);
    }
    public static boolean supports(LevelReader level, BlockPos pos, Direction side) {
        var backing = pos.relative(side);
        return loaded(level, backing) && completeSupport(level.getBlockState(backing), level, backing, side.getOpposite());
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var face = context.getClickedFace(); if (!face.getAxis().isHorizontal()) return null;
        var side = face.getOpposite(); var level = context.getLevel(); var pos = context.getClickedPos();
        if (!supports(level, pos, side)) return null;
        var current = level.getBlockState(pos);
        if (!current.getFluidState().isEmpty()) return null;
        return (current.is(this) ? current : defaultBlockState()).setValue(property(side), true);
    }
    @Override protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        if (context.isSecondaryUseActive() || !context.getItemInHand().is(asItem()) || !context.getClickedFace().getAxis().isHorizontal()) return false;
        var side = context.getClickedFace().getOpposite();
        return !state.getValue(property(side)) && supports(context.getLevel(), context.getClickedPos(), side);
    }
    @Override public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        for (var side : SIDES) if (state.getValue(property(side)) && supports(level, pos, side)) return true;
        return false;
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!direction.getAxis().isHorizontal() || !state.getValue(property(direction))) return state;
        if (completeSupport(neighbor, level, neighborPos, direction.getOpposite())) return state;
        var next = state.setValue(property(direction), false);
        return mask(next) == 0 ? Blocks.AIR.defaultBlockState() : next;
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPES[mask(state)]; }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var tool = params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL);
        return tool != null && tool.canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SHEARS_DIG)
                ? List.of(new ItemStack(asItem())) : List.of();
    }

    private record Target(BlockPos pos, BlockState before, BlockState after) {}
    private static boolean adjacentFace(LevelReader level, BlockPos pos, BlockState state, Direction side) {
        for (var existing : SIDES) if (state.getValue(property(existing)) && existing.getAxis() != side.getAxis()
                && supports(level, pos, existing)) return true;
        return false;
    }
    private List<Target> targets(LevelReader level, BlockPos pos, BlockState state) {
        if (!loaded(level, pos) || !level.getBlockState(pos).equals(state) || !canSurvive(state, level, pos)
                || PlantEnvironment.isCold(level, pos)) return List.of();
        var result = new ArrayList<Target>();
        for (var side : SIDES) {
            if (!state.getValue(property(side)) && adjacentFace(level, pos, state, side) && supports(level, pos, side))
                result.add(new Target(pos, state, state.setValue(property(side), true)));
            if (!state.getValue(property(side)) || !supports(level, pos, side)) continue;
            for (var along : Direction.values()) {
                if (along.getAxis() == side.getAxis()) continue;
                var target = pos.relative(along);
                if (!loaded(level, target) || !supports(level, target, side) || PlantEnvironment.isCold(level, target)) continue;
                var before = level.getBlockState(target);
                if (before.is(this) && !before.getValue(property(side))) result.add(new Target(target, before, before.setValue(property(side), true)));
                else if (before.isAir()) result.add(new Target(target, before, defaultBlockState().setValue(property(side), true)));
            }
        }
        return result;
    }
    @Override public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) { return !targets(level, pos, state).isEmpty(); }
    // Vanilla already checked candidates; commit rechecks them. Avoid a third neighborhood scan per use.
    @Override public boolean isBonemealSuccess(Level level, net.minecraft.util.RandomSource random, BlockPos pos, BlockState state) { return true; }
    @Override public void performBonemeal(ServerLevel level, net.minecraft.util.RandomSource random, BlockPos pos, BlockState state) {
        var candidates = targets(level, pos, state); if (candidates.isEmpty()) return;
        var target = candidates.get(random.nextInt(candidates.size()));
        if (!level.getWorldBorder().isWithinBounds(target.pos()) || !level.getBlockState(target.pos()).equals(target.before())) return;
        level.setBlock(target.pos(), target.after(), Block.UPDATE_ALL);
    }
    @Override protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        var result = defaultBlockState(); for (var side : SIDES) result = result.setValue(property(rotation.rotate(side)), state.getValue(property(side))); return result;
    }
    @Override protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        var result = defaultBlockState(); for (var side : SIDES) result = result.setValue(property(mirror.mirror(side)), state.getValue(property(side))); return result;
    }
}
