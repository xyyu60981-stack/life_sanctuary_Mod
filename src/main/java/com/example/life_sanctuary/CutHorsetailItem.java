package com.example.life_sanctuary;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla placement handles permissions, sound and consumption; only detached stems can merge. */
public final class CutHorsetailItem extends BlockItem {
    private final boolean spore;

    public CutHorsetailItem(Block block, Properties properties, boolean spore) {
        super(block, properties);
        this.spore = spore;
    }

    public boolean canAddTo(BlockState state) {
        return state.getBlock() instanceof PlacedHorsetailBlock
                && (spore ? !state.getValue(EquisetumArvenseBlock.SPORE_STEM)
                          : state.getValue(PlacedHorsetailBlock.STEMS) < 3);
    }

    @Override
    protected BlockState getPlacementState(BlockPlaceContext context) {
        BlockState existing = context.getLevel().getBlockState(context.getClickedPos());
        if (!canAddTo(existing)) return super.getPlacementState(context);
        BlockState next = spore ? existing.setValue(EquisetumArvenseBlock.SPORE_STEM, true)
                : existing.setValue(PlacedHorsetailBlock.STEMS, existing.getValue(PlacedHorsetailBlock.STEMS) + 1);
        return canPlace(context, next) ? next : null;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return spore ? 10 : super.getUseDuration(stack, entity);
    }

    @Override
    public String getDescriptionId() {
        return spore ? "block.life_sanctuary.equisetum_arvense_spore_stem"
                : "block.life_sanctuary.equisetum_arvense";
    }
}
