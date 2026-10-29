package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootParams;

/** Shared dry geometry. Species support, environment and growth belong to the registered individual. */
public final class SharedGametophyteBlock extends Block implements WaterDependentPlant {
    public static final MapCodec<SharedGametophyteBlock> CODEC = simpleCodec(SharedGametophyteBlock::new);
    public static final BooleanProperty WITHERING = BooleanProperty.create("withering");
    public SharedGametophyteBlock(Properties properties) {
        super(properties.noCollission().noOcclusion().pushReaction(PushReaction.DESTROY));
        registerDefaultState(defaultBlockState().setValue(WITHERING, false));
    }
    @Override public MapCodec<SharedGametophyteBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(WITHERING); }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) { return List.of(); }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return state.is(LifeSanctuaryMod.SPHENOPHYTE_GAMETOPHYTE.get())
                ? EquisetumArvenseGametophyteBlock.OUTLINE : super.getShape(state, level, pos, context);
    }
    @Override public java.util.Optional<WaterDependentPlant.Requirement> waterRequirement(ServerLevel level, BlockPos pos, BlockState state) {
        return SharedGametophyteWater.requirement(level, pos);
    }
    @Override public void waterStatusChanged(ServerLevel level, BlockPos pos, BlockState state, boolean dry) {
        SharedGametophyteWater.changed(level, pos, dry);
    }
    @Override public boolean finishWaterDecline(ServerLevel level, BlockPos pos, BlockState state) {
        return SharedGametophyteWater.finish(level, pos);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        if (state.is(LifeSanctuaryMod.SPHENOPHYTE_GAMETOPHYTE.get())
                && HorsetailPopulations.get(level).member(pos) != null)
            EquisetumArvenseGametophyteBlock.advance(level, pos);
    }
    @Override protected BlockState updateShape(BlockState state, net.minecraft.core.Direction direction,
            BlockState neighbor, net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == net.minecraft.core.Direction.DOWN && level instanceof ServerLevel server
                && state.is(LifeSanctuaryMod.SPHENOPHYTE_GAMETOPHYTE.get())
                && HorsetailPopulations.get(server).member(pos) != null
                && !neighbor.is(LifeSanctuaryMod.HORSETAIL_SUBSTRATE))
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!next.is(this) && level instanceof ServerLevel server) {
            if (state.is(LifeSanctuaryMod.SPHENOPHYTE_GAMETOPHYTE.get())
                    && !HorsetailPopulations.isLivingHorsetail(next)
                    && HorsetailPopulations.get(server).member(pos) != null) {
                HorsetailPopulations.get(server).remove(pos);
                PlantWaterMonitoring.get(server).remove(pos);
            }
            var store = PlantIndividuals.get(server);
            var plant = store.atPart(pos).orElse(null);
            if (plant != null && !store.isReadOnly() && !store.isApplyingStructure(plant.id())) {
                var parts = store.partsOf(plant.id());
                if (parts.size() == 1 && plant.base().equals(pos)) store.remove(plant.id(), plant.base());
                else {
                    // Unexpected partial destruction preserves ownership for diagnosis instead of freeing the population slot.
                    HeterosporeWorldData.get(server).freezeForRecovery(plant);
                    PlantLifecycle.setPauseReason(store, plant.id(), "gametophyte_structure_error", true);
                }
            }
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
