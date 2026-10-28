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
    /** Override for containers that must atomically exchange an input and output. */
    default boolean exchangeSporeBag(String speciesId, BlockPos source) {
        if (!hasEmptySporeBag()) return false;
        consumeEmptySporeBag();
        giveSporeBag(speciesId, source);
        return true;
    }
    /** Differentiated spores require an adapter that explicitly handles the selected bag form. */
    default boolean exchangeSporeBag(String speciesId, SporeBagContents.Form form, BlockPos source) {
        var species = PlantSporeTraits.species(speciesId).orElse(null);
        if (species == null || !SporeBagContents.allowed(PlantSporeTraits.find(species), form)) return false;
        return form == SporeBagContents.Form.NORMAL && exchangeSporeBag(speciesId, source);
    }
    /** Whether an empty spore bag is available in the permitted collection source. */
    boolean hasEmptySporeBag();

    /** Consumes exactly one empty bag after a harvest has been accepted. */
    void consumeEmptySporeBag();

    /** Delivers the harvested species-specific spore bag. */
    void giveSporeBag(String speciesId, BlockPos source);
}
