package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.HitResult;

/** Coalified stem embedded in stone or deepslate. Carrier survives mining and normal BlockItem placement. */
public class EmbeddedCoalifiedBlock extends RotatedPillarBlock implements FossilMaterial {
    public static final EnumProperty<FossilMaterialRules.Matrix> MATRIX = EnumProperty.create("matrix", FossilMaterialRules.Matrix.class);
    public static final MapCodec<EmbeddedCoalifiedBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(), ResourceLocation.CODEC.fieldOf("specimen").forGetter(block -> block.specimen.id())
    ).apply(instance, EmbeddedCoalifiedBlock::new));
    private final FossilMaterialRules.Specimen specimen;
    public EmbeddedCoalifiedBlock(Properties properties, ResourceLocation specimenId) {
        super(properties.requiresCorrectToolForDrops());
        specimen = new FossilMaterialRules.Specimen(specimenId, FossilMaterialRules.Form.EMBEDDED_COALIFIED);
        registerDefaultState(defaultBlockState().setValue(MATRIX, FossilMaterialRules.Matrix.STONE));
    }
    @Override public MapCodec<? extends EmbeddedCoalifiedBlock> codec() { return CODEC; }
    @Override public FossilMaterialRules.Specimen fossil() { return specimen; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(MATRIX);
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) { return FossilMaterial.drops(state, params); }
    @Override public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return FossilMaterial.decoration(state);
    }
}
