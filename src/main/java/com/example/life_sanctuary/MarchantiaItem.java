package com.example.life_sanctuary;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class MarchantiaItem extends BlockItem {
    public MarchantiaItem(Block block, Properties properties) { super(block, properties); }
    @Override protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        // No cabinet stage is registered yet: never reinterpret a foreign/damaged cultivated item.
        if (CultivatedStageData.hasStoredStage(context.getItemInHand())) return false;
        if (!(context.getLevel() instanceof ServerLevel level)) return super.placeBlock(context, state);
        return MarchantiaRules.plant(level, context.getClickedPos()).success();
    }
}
