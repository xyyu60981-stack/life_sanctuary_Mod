package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;

/** Flower implementations deduct their own quota only after this exchange succeeds. */
public interface PollenHarvestAccess {
    boolean exchangePollenBag(String speciesId, BlockPos source);
}
