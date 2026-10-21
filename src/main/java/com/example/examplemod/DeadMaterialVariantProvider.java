package com.example.examplemod;

import net.minecraft.world.level.block.state.BlockState;

/** Supplies the dead-material state matching a plant's visual/material variant. */
public interface DeadMaterialVariantProvider {
    BlockState deadMaterialVariant(BlockState livingState);
}
