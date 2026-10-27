package com.example.life_sanctuary;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** One slice item with stable specimen/form/carrier metadata; artwork can vary without adding item IDs. */
public final class FossilSliceItem extends Item {
    public FossilSliceItem(Properties properties) { super(properties); }
    public ItemStack fromInput(ItemStack input) {
        var sample = FossilMaterial.identify(input);
        if (sample.isEmpty()) return ItemStack.EMPTY;
        var result = new ItemStack(this);
        new FossilSliceData(sample.get(), BuiltInRegistries.ITEM.getKey(input.getItem())).write(result);
        return result;
    }
    @Override public Component getName(ItemStack stack) {
        var data = FossilSliceData.read(stack);
        if (data.isPresent()) {
            var source = BuiltInRegistries.ITEM.getOptional(data.get().sourceItem());
            if (source.isPresent() && source.get() instanceof BlockItem item && item.getBlock() instanceof FossilMaterial fossil
                    && fossil.fossil().equals(data.get().sample().specimen()))
                return fossil.sliceName();
        }
        return super.getName(stack);
    }
}
