package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Tansachana-specific adult trunk geometry. All cells are parts of one centered-base individual. */
public final class TansachanaCycad {
    private TansachanaCycad() {}
    public static final CycadGrowthProfile PROFILE = new CycadGrowthProfile(
            ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "cycas_tansachana"),
            15, 20, 0, 2, 3, 2, 3, 15, 20, true);
    /** XYZ 3x3x3 or 3x4x3; base is the central bottom trunk block, never the corner. */
    public static Set<BlockPos> trunkPositions(BlockPos base, int height) {
        if (height != 3 && height != 4) throw new IllegalArgumentException("Tansachana trunk height must be 3 or 4");
        var result = new HashSet<BlockPos>();
        for (int x = -1; x <= 1; x++) for (int y = 0; y < height; y++) for (int z = -1; z <= 1; z++)
            result.add(new BlockPos(Math.addExact(base.getX(), x), Math.addExact(base.getY(), y), Math.addExact(base.getZ(), z)));
        return Set.copyOf(result);
    }
    /** No world reads: the species supplies a complete adult structure and its actual trunk role. */
    public static boolean hasTrunk(PlantStageStructure target, BlockPos base, int height, String trunkRole) {
        if (trunkRole == null || trunkRole.isBlank()) throw new IllegalArgumentException("Explicit trunk role required");
        if (!target.stage().equals(CycadGrowthProfile.ADULT)) return false;
        var required = trunkPositions(base, height);
        for (var pos : required) {
            var part = target.parts().get(pos);
            if (part == null || !part.role().equals(trunkRole)) return false;
        }
        return target.parts().entrySet().stream().noneMatch(entry -> entry.getValue().role().equals(trunkRole) && !required.contains(entry.getKey()));
    }
}
