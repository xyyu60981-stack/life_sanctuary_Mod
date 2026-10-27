package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;

/** Complete stone cube with a visible fossil face; no deepslate imprint is implicitly created. */
public class PlantImprintBlock extends Block implements FossilMaterial {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final MapCodec<PlantImprintBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(), ResourceLocation.CODEC.fieldOf("specimen").forGetter(block -> block.specimen.id())
    ).apply(instance, PlantImprintBlock::new));
    private final FossilMaterialRules.Specimen specimen;
    public PlantImprintBlock(Properties properties, ResourceLocation specimenId) {
        super(properties.requiresCorrectToolForDrops());
        specimen = new FossilMaterialRules.Specimen(specimenId, FossilMaterialRules.Form.STONE_IMPRINT);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.UP));
    }
    @Override public MapCodec<? extends PlantImprintBlock> codec() { return CODEC; }
    @Override public FossilMaterialRules.Specimen fossil() { return specimen; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getClickedFace()); }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.setValue(FACING, mirror.mirror(state.getValue(FACING))); }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) { return FossilMaterial.drops(state, params); }
}
