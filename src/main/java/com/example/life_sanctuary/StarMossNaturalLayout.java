package com.example.life_sanctuary;

import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** Four to eight tiles in one to four separate natural groups per 16x16 region. */
public final class StarMossNaturalLayout {
    private StarMossNaturalLayout() {}
    public static CarpetMossNaturalLayout.Layout plan(Collection<BlockPos> surfaces, RandomSource random) {
        return CarpetMossNaturalLayout.plan(surfaces, random, new CarpetMossNaturalLayout.Limits(
                StarMossRules.NATURAL_MIN, StarMossRules.NATURAL_MAX,
                StarMossRules.NATURAL_GROUP_MIN, StarMossRules.NATURAL_GROUP_MAX));
    }
}
