package com.example.life_sanctuary;

import java.util.ArrayList;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/** Harvest-time only. Explicit crop/product pairs avoid treating seeds or unrelated loot as produce. */
public final class PeatCropHarvestHandler {
    private static Item produce(BlockState state) {
        if (state.is(Blocks.WHEAT)) return Items.WHEAT;
        if (state.is(Blocks.CARROTS)) return Items.CARROT;
        if (state.is(Blocks.POTATOES)) return Items.POTATO;
        if (state.is(Blocks.BEETROOTS)) return Items.BEETROOT;
        return null; // Other mods require explicit harvest integration; no melon/pumpkin bonus.
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDrops(BlockDropsEvent event) {
        BlockState state = event.getState();
        Item produce = produce(state);
        if (produce == null || !(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) return;
        if (!event.getLevel().getBlockState(event.getPos().below()).is(LifeSanctuaryMod.PEAT_FARMLAND.get())) return;

        var extras = new ArrayList<ItemEntity>();
        for (ItemEntity drop : event.getDrops()) {
            var stack = drop.getItem();
            if (!stack.is(produce) || stack.isEmpty()) continue;
            long scaled = (long) stack.getCount() * 3;
            int bonus = (int) (scaled / 10);
            // Fractional yield is probabilistically rounded: one wheat gets 30% chance of one extra.
            // This avoids permanently rounding every one-item harvest back down to zero bonus.
            if (scaled % 10 > 0 && event.getLevel().random.nextInt(10) < scaled % 10) bonus++;
            while (bonus > 0) {
                int count = Math.min(bonus, stack.getMaxStackSize());
                var extra = new ItemEntity(event.getLevel(), drop.getX(), drop.getY(), drop.getZ(), stack.copyWithCount(count));
                extra.setDefaultPickUpDelay();
                extras.add(extra);
                bonus -= count;
            }
        }
        event.getDrops().addAll(extras);
    }
}
