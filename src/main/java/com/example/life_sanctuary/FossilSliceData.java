package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Slice identity is generated from the actual input block, without copying arbitrary input components. */
public record FossilSliceData(FossilMaterial.Sample sample, ResourceLocation sourceItem) {
    private static final String KEY = "FossilSlice";
    public FossilSliceData {
        java.util.Objects.requireNonNull(sample); java.util.Objects.requireNonNull(sourceItem);
        if (sourceItem.equals(ResourceLocation.withDefaultNamespace("air")) || sourceItem.toString().length() > 256
                || sample.specimen().form() == FossilMaterialRules.Form.FULL_COALIFIED && sample.matrix().isPresent()
                || sample.specimen().form() == FossilMaterialRules.Form.EMBEDDED_COALIFIED && sample.matrix().isEmpty()
                || sample.specimen().form() == FossilMaterialRules.Form.STONE_IMPRINT
                    && !sample.matrix().equals(Optional.of(FossilMaterialRules.Matrix.STONE)))
            throw new IllegalArgumentException("Invalid fossil slice identity");
    }
    public CompoundTag save() {
        var saved = new CompoundTag(); saved.putInt("Version", 1); saved.putString("SourceItem", sourceItem.toString());
        saved.put("Specimen", FossilMaterialRules.Specimen.CODEC.encodeStart(NbtOps.INSTANCE, sample.specimen()).getOrThrow());
        sample.matrix().ifPresent(matrix -> saved.putString("Matrix", matrix.name())); return saved;
    }
    public static Optional<FossilSliceData> read(CompoundTag saved) {
        try {
            if (!saved.contains("Version", Tag.TAG_INT) || saved.getInt("Version") != 1
                    || !saved.contains("SourceItem", Tag.TAG_STRING) || !saved.contains("Specimen", Tag.TAG_COMPOUND)
                    || saved.contains("Matrix") && !saved.contains("Matrix", Tag.TAG_STRING)) return Optional.empty();
            var specimen = FossilMaterialRules.Specimen.CODEC.parse(NbtOps.INSTANCE, saved.getCompound("Specimen")).result();
            if (specimen.isEmpty()) return Optional.empty();
            var matrix = saved.contains("Matrix") ? Optional.of(FossilMaterialRules.Matrix.valueOf(saved.getString("Matrix"))) : Optional.<FossilMaterialRules.Matrix>empty();
            return Optional.of(new FossilSliceData(new FossilMaterial.Sample(specimen.get(), matrix), ResourceLocation.parse(saved.getString("SourceItem"))));
        } catch (IllegalArgumentException error) { return Optional.empty(); }
    }
    public void write(ItemStack slice) {
        var root = slice.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); root.put(KEY, save());
        slice.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }
    public static Optional<FossilSliceData> read(ItemStack stack) {
        if (!(stack.getItem() instanceof FossilSliceItem)) return Optional.empty();
        var root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return root.contains(KEY, Tag.TAG_COMPOUND) ? read(root.getCompound(KEY)) : Optional.empty();
    }
}
