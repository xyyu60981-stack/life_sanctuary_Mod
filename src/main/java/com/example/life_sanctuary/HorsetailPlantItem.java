package com.example.life_sanctuary;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** Block item adapter that records the source assigned to a placed individual. */
public final class HorsetailPlantItem extends BlockItem {
    private final PlantSource source;

    public HorsetailPlantItem(Block block, Properties properties) {
        this(block, properties, PlantSource.PLAYER);
    }

    public HorsetailPlantItem(Block block, Properties properties, PlantSource source) {
        super(block, properties);
        this.source = source;
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        InteractionResult result = super.place(context);
        if (result.consumesAction() && context.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            var pos = context.getClickedPos();
            if (level.getBlockState(pos).is(getBlock())) {
                HorsetailPopulations populations = HorsetailPopulations.get(level);
                if (source == PlantSource.ONE_TIME) populations.markOneTimePlaced(level, pos);
                else populations.markPlayerPlaced(level, pos);
                if (getBlock() == LifeSanctuaryMod.EQUISETUM_ARVENSE.get()) level.scheduleTick(pos, getBlock(), 1);
            }
        }
        return result;
    }
}
