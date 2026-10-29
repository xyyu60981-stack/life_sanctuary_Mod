package com.example.life_sanctuary;

import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** Species parameters for the shared bounded carpet-patch planner. */
public final class MarchantiaNaturalLayout {
    private MarchantiaNaturalLayout() {}
    public record Layout(List<List<BlockPos>> clusters) {
        public Layout { clusters = clusters.stream().map(List::copyOf).toList(); }
        public List<BlockPos> positions() { return clusters.stream().flatMap(List::stream).toList(); }
    }
    public static boolean neighbors(BlockPos first, BlockPos second) {
        return CarpetMossNaturalLayout.neighbors(first, second);
    }
    public static Layout plan(Collection<BlockPos> surfaces, RandomSource random) {
        var limits = new CarpetMossNaturalLayout.Limits(MarchantiaRules.NATURAL_MIN, MarchantiaRules.NATURAL_MAX,
                MarchantiaRules.NATURAL_GROUP_MIN, MarchantiaRules.NATURAL_GROUP_MAX);
        return new Layout(CarpetMossNaturalLayout.plan(surfaces, random, limits).clusters());
    }
}
