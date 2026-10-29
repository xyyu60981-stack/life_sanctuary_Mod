package com.example.life_sanctuary;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla permissions and sounds surround the shared world/identity transaction. */
public final class LuminousMossItem extends BlockItem {
    public LuminousMossItem(Block block, Properties properties) { super(block, properties); }
    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext context,
            java.util.List<net.minecraft.network.chat.Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        String key = !CultivatedStageData.hasStoredStage(stack) ? "tooltip.life_sanctuary.luminous_moss.seedling"
                : LuminousMossCultivation.accepts(stack) ? "tooltip.life_sanctuary.luminous_moss.cultivated"
                : "tooltip.life_sanctuary.cultivation.invalid_product";
        tooltip.add(net.minecraft.network.chat.Component.translatable(key)
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
    @Override protected BlockState getPlacementState(BlockPlaceContext context) {
        var stack = context.getItemInHand();
        boolean cultivated = CultivatedStageData.hasStoredStage(stack);
        if (cultivated && !LuminousMossCultivation.accepts(stack)) return null;
        var state = super.getPlacementState(context);
        return state == null ? null : state.setValue(LuminousMossBlock.AGE, cultivated ? 1 : 0);
    }
    @Override protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        var stack = context.getItemInHand();
        boolean cultivated = CultivatedStageData.hasStoredStage(stack);
        if (cultivated && !LuminousMossCultivation.accepts(stack)) return false;
        if (!(context.getLevel() instanceof ServerLevel level)) return super.placeBlock(context, state);
        if (cultivated) return LuminousMossRules.plantCultivatedAdult(level, context.getClickedPos()).success();
        return LuminousMossRules.plant(level, context.getClickedPos(), PlantSource.PLAYER,
                PlantOrigin.PLAYER_CULTIVATED).success();
    }
}
