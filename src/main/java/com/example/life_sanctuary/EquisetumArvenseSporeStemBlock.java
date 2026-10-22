package com.example.life_sanctuary;

/** Detached spore stems never create a fresh harvest quota. */
public final class EquisetumArvenseSporeStemBlock extends PlacedHorsetailBlock {
    public EquisetumArvenseSporeStemBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(STEMS, 0)
                .setValue(EquisetumArvenseBlock.SPORE_STEM, true));
    }
}
