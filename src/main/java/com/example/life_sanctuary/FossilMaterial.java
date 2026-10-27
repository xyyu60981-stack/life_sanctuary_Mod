package com.example.life_sanctuary;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/** The material block owns its specimen; client-provided Species custom data cannot change fossil identity. */
public interface FossilMaterial {
    FossilMaterialRules.Specimen fossil();
    default net.minecraft.network.chat.Component sliceName() {
        return net.minecraft.network.chat.Component.translatable("item.life_sanctuary.fossil_slice.of",
                ((net.minecraft.world.level.block.Block) this).asItem().getDescription());
    }
    default boolean fitsHost(BlockState state, FossilMaterialRules.Matrix host) {
        if (state.getBlock() != this) return false;
        if (fossil().form() == FossilMaterialRules.Form.EMBEDDED_COALIFIED && !state.hasProperty(EmbeddedCoalifiedBlock.MATRIX)) return false;
        var matrix = fossil().form() == FossilMaterialRules.Form.EMBEDDED_COALIFIED ? state.getValue(EmbeddedCoalifiedBlock.MATRIX) : null;
        return FossilMaterialRules.fits(fossil(), matrix, host);
    }
    record Sample(FossilMaterialRules.Specimen specimen, Optional<FossilMaterialRules.Matrix> matrix) {
        public Sample { java.util.Objects.requireNonNull(specimen); java.util.Objects.requireNonNull(matrix); }
    }
    static Optional<Sample> identify(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof FossilMaterial material)) return Optional.empty();
        var state = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY).apply(item.getBlock().defaultBlockState());
        if (material.fossil().form() == FossilMaterialRules.Form.EMBEDDED_COALIFIED && !state.hasProperty(EmbeddedCoalifiedBlock.MATRIX)) return Optional.empty();
        var matrix = material.fossil().form() == FossilMaterialRules.Form.EMBEDDED_COALIFIED
                ? Optional.of(state.getValue(EmbeddedCoalifiedBlock.MATRIX)) : material.fossil().form() == FossilMaterialRules.Form.STONE_IMPRINT
                ? Optional.of(FossilMaterialRules.Matrix.STONE) : Optional.<FossilMaterialRules.Matrix>empty();
        return Optional.of(new Sample(material.fossil(), matrix));
    }
    static ItemStack decoration(BlockState state) {
        var stack = new ItemStack(state.getBlock());
        if (!stack.isEmpty() && state.getBlock() instanceof EmbeddedCoalifiedBlock)
            stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(EmbeddedCoalifiedBlock.MATRIX, state));
        return stack;
    }
    static List<ItemStack> drops(BlockState state, LootParams.Builder params) {
        var tool = params.getOptionalParameter(LootContextParams.TOOL);
        if (tool == null || FossilMaterialRules.dropCount(tool.is(ItemTags.PICKAXES), tool.isCorrectToolForDrops(state)) == 0) return List.of();
        var stack = decoration(state);
        return stack.isEmpty() ? List.of() : List.of(stack);
    }
}
