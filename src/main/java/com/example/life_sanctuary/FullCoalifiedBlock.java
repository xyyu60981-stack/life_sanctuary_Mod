package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;

/** Full coalified log/fiber body, three baked axis states, no living identity or ticker. */
public class FullCoalifiedBlock extends RotatedPillarBlock implements FossilMaterial {
    public static final MapCodec<FullCoalifiedBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(), ResourceLocation.CODEC.fieldOf("specimen").forGetter(block -> block.specimen.id())
    ).apply(instance, FullCoalifiedBlock::new));
    private final FossilMaterialRules.Specimen specimen;
    public FullCoalifiedBlock(Properties properties, ResourceLocation specimenId) {
        super(properties.requiresCorrectToolForDrops());
        specimen = new FossilMaterialRules.Specimen(specimenId, FossilMaterialRules.Form.FULL_COALIFIED);
    }
    @Override public MapCodec<? extends FullCoalifiedBlock> codec() { return CODEC; }
    @Override public FossilMaterialRules.Specimen fossil() { return specimen; }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) { return FossilMaterial.drops(state, params); }
}
