package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;

/** Use the native block-state component so placement preserves the same visual state without a second item. */
public final class CycadSeedCoat {
    private CycadSeedCoat() {}
    public static Optional<Boolean> read(BlockItemStateProperties properties) {
        var value = properties.properties().get("seed_coat");
        if (value == null || value.equals("true")) return Optional.of(true);
        return value.equals("false") ? Optional.of(false) : Optional.empty();
    }
    public static Optional<Boolean> read(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof CycadSeedItem
                ? read(stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)) : Optional.empty();
    }
    public static BlockItemStateProperties peeled(BlockItemStateProperties properties) {
        if (read(properties).isEmpty()) throw new IllegalArgumentException("Invalid seed coat component");
        return properties.with(CycadSeedAppearance.SEED_COAT, false);
    }
    /** Crafting preview is a copy; the original stack and all unrelated components remain intact. */
    public static ItemStack peel(ItemStack source) {
        if (!read(source).orElse(false)) return ItemStack.EMPTY;
        var result = source.copyWithCount(1);
        result.set(DataComponents.BLOCK_STATE, peeled(source.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)));
        return result;
    }
    /** Species drop/clone adapters use this, rather than dropping an unconditionally coated default seed. */
    public static ItemStack fromState(BlockState state) {
        if (!(state.getBlock() instanceof CycadSeedAppearance) || !state.hasProperty(CycadSeedAppearance.SEED_COAT)) return ItemStack.EMPTY;
        var result = new ItemStack(state.getBlock());
        if (!(result.getItem() instanceof CycadSeedItem)) return ItemStack.EMPTY;
        if (!state.getValue(CycadSeedAppearance.SEED_COAT)) result.set(DataComponents.BLOCK_STATE,
                BlockItemStateProperties.EMPTY.with(CycadSeedAppearance.SEED_COAT, false));
        return result;
    }
}
