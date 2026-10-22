package com.example.life_sanctuary;

import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.ItemAbilities;

public final class PeatBlock extends Block {
    public PeatBlock(Properties properties) { super(properties); }

    @Override
    public BlockState getToolModifiedState(BlockState state, UseOnContext context, ItemAbility ability, boolean simulate) {
        if (ability == ItemAbilities.HOE_TILL && context.getItemInHand().canPerformAction(ability)
                && HoeItem.onlyIfAirAbove(context)) {
            return LifeSanctuaryMod.PEAT_FARMLAND.get().defaultBlockState();
        }
        return super.getToolModifiedState(state, context, ability, simulate);
    }
}
