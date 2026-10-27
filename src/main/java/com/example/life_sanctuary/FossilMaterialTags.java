package com.example.life_sanctuary;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/** Activate with actual registered fossil blocks/items; do not silently make any other material pickaxe-mineable. */
public final class FossilMaterialTags extends BlockTagsProvider {
    public static final TagKey<Block> MATERIALS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("life_sanctuary", "fossil_materials"));
    private final List<Block> materials;
    public FossilMaterialTags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup,
            ExistingFileHelper existingFiles, List<? extends Block> materials) {
        super(output, lookup, "life_sanctuary", existingFiles);
        this.materials = List.copyOf(materials);
        if (materials.isEmpty()) throw new IllegalArgumentException("No registered fossil materials supplied");
    }
    @Override protected void addTags(HolderLookup.Provider registries) {
        for (var block : materials)
            if (!(block instanceof FossilMaterial) || block.asItem() == Items.AIR)
                throw new IllegalArgumentException("Fossil materials need their actual block and collectible item");
        var blocks = materials.toArray(Block[]::new);
        tag(BlockTags.MINEABLE_WITH_PICKAXE).add(LifeSanctuaryMod.FOSSIL_SLICER.get()).add(blocks);
        tag(MATERIALS).add(blocks);
    }
}
