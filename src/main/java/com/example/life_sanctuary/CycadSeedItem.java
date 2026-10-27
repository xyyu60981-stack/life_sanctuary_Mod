package com.example.life_sanctuary;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** One normal block item per species; coated and peeled seeds share name, substrate rules and placement. */
public class CycadSeedItem extends BlockItem {
    public CycadSeedItem(Block block, Properties properties) {
        super(block, properties);
        if (!(block instanceof CycadSeedAppearance seed) || seed.species() == null || seed.seedStage() == null || seed.seedStage().isBlank()
                || !block.defaultBlockState().hasProperty(CycadSeedAppearance.SEED_COAT)
                || !block.defaultBlockState().getValue(CycadSeedAppearance.SEED_COAT) || (seed.coatColor() & 0xFF000000) != 0)
            throw new IllegalArgumentException("Cycad seed block needs species, seed stage, RGB coat color and default seed_coat=true");
    }
    public CycadSeedAppearance appearance() { return (CycadSeedAppearance) getBlock(); }
    /** Mature-harvest adapters create a new default stack, not a peeled seed copied from the inventory. */
    public ItemStack collected(int count) {
        var result = new ItemStack(this, count);
        if (count < 1 || count > result.getMaxStackSize()) throw new IllegalArgumentException("Harvest stack exceeds seed stack limit");
        return result;
    }
    @Override public InteractionResult place(BlockPlaceContext context) {
        if (CycadSeedCoat.read(context.getItemInHand()).isEmpty()) return InteractionResult.FAIL;
        return super.place(context);
    }
}
