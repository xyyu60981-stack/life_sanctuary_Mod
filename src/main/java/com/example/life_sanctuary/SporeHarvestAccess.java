package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;

/**
 * Inventory boundary for reproductive-structure harvesting.
 *
 * <p>The first implementation deliberately does not depend on a registered
 * empty-bag item. A later player or collection-box adapter can provide the
 * inventory checks without changing plant lifecycle code.</p>
 */
public interface SporeHarvestAccess {
    /** Whether an empty spore bag is available in the permitted collection source. */
    boolean hasEmptySporeBag();

    /** Consumes exactly one empty bag after a harvest has been accepted. */
    void consumeEmptySporeBag();

    /** Delivers the harvested species-specific spore bag. */
    void giveSporeBag(String speciesId, BlockPos source);
}
