package com.example.examplemod;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** Manual placement is the only automatic source of the PLAYER label. */
public final class HorsetailPlantItem extends BlockItem {
    public HorsetailPlantItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        InteractionResult result = super.place(context);
        if (result.consumesAction() && context.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            var pos = context.getClickedPos();
            if (level.getBlockState(pos).is(getBlock())) {
                HorsetailPopulations.get(level).markPlayerPlaced(level, pos);
                if (getBlock() == ExampleMod.EQUISETUM_ARVENSE.get()) level.scheduleTick(pos, getBlock(), 1);
            }
        }
        return result;
    }
}
