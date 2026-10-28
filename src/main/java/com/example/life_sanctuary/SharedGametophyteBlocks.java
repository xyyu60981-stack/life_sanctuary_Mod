package com.example.life_sanctuary;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Setup-time binding of real shared blocks. Species remain in saved individual metadata. */
public final class SharedGametophyteBlocks {
    private SharedGametophyteBlocks() {}
    private static final Map<GametophyteAppearance, Block> BLOCKS = new EnumMap<>(GametophyteAppearance.class);
    private static final Map<Block, GametophyteAppearance> APPEARANCES = new IdentityHashMap<>();

    public static synchronized void register(GametophyteAppearance appearance, Block block) {
        java.util.Objects.requireNonNull(appearance);
        java.util.Objects.requireNonNull(block);
        if (BLOCKS.containsKey(appearance) || APPEARANCES.containsKey(block))
            throw new IllegalArgumentException("Duplicate shared gametophyte block or appearance");
        BLOCKS.put(appearance, block);
        APPEARANCES.put(block, appearance);
    }

    /** Empty means the real shared material has not been registered; never substitutes another group. */
    public static synchronized Optional<Block> find(ResourceLocation species, SporeBagContents.Form form) {
        return GametophyteAppearance.forSpecies(species, form).map(BLOCKS::get);
    }

    /** Once a block is bound, every species using that appearance must use that same block. */
    public static synchronized boolean permits(ResourceLocation species, SporeBagContents.Form form, BlockState state) {
        var wanted = GametophyteAppearance.forSpecies(species, form).orElse(null);
        if (wanted == null) return false;
        var actual = APPEARANCES.get(state.getBlock());
        var bound = BLOCKS.get(wanted);
        return bound == null ? actual == null : state.is(bound);
    }
}
