package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/** Burned bodies cannot ignite. Silk touch returns their actual block item, ordinary breaking yields ash. */
public class BurnedMaterialBlock extends RotatedPillarBlock {
    public static final MapCodec<BurnedMaterialBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(), StringRepresentable.fromEnum(CharredMaterialRules.Kind::values).fieldOf("kind").forGetter(block -> block.kind)
    ).apply(instance, BurnedMaterialBlock::new));
    private final CharredMaterialRules.Kind kind;
    public BurnedMaterialBlock(Properties properties, CharredMaterialRules.Kind kind) {
        super(properties.lightLevel(state -> 0)); this.kind = java.util.Objects.requireNonNull(kind);
    }
    @Override public MapCodec<? extends BurnedMaterialBlock> codec() { return CODEC; }
    public CharredMaterialRules.Kind kind() { return kind; }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (CharredMaterialBlock.silk(params.getLevel(), params.getOptionalParameter(LootContextParams.TOOL))) {
            var item = new ItemStack(this);
            return item.isEmpty() ? List.of() : List.of(item);
        }
        return List.of(new ItemStack(Items.BONE_MEAL, CharredMaterialRules.boneMealCount(kind, false)));
    }
    @Override public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return 0; }
    @Override public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return 0; }
}
