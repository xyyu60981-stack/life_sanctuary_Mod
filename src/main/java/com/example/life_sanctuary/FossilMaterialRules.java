package com.example.life_sanctuary;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/** Stable slicing identity and carrier policy, independent of textures and translations. */
public final class FossilMaterialRules {
    private FossilMaterialRules() {}
    public enum Form implements StringRepresentable {
        FULL_COALIFIED, EMBEDDED_COALIFIED, STONE_IMPRINT;
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public enum Matrix implements StringRepresentable {
        STONE, DEEPSLATE;
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public record Specimen(ResourceLocation id, Form form) {
        public static final com.mojang.serialization.Codec<Specimen> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("specimen").forGetter(Specimen::id),
                StringRepresentable.fromEnum(Form::values).fieldOf("form").forGetter(Specimen::form)
        ).apply(instance, Specimen::new));
        public Specimen {
            java.util.Objects.requireNonNull(id); java.util.Objects.requireNonNull(form);
            if (id.equals(ResourceLocation.withDefaultNamespace("air")) || id.toString().length() > 256)
                throw new IllegalArgumentException("Invalid fossil specimen identity");
        }
        public boolean supports(Matrix matrix) {
            java.util.Objects.requireNonNull(matrix);
            return form != Form.STONE_IMPRINT || matrix == Matrix.STONE;
        }
    }
    public static boolean fits(Specimen specimen, Matrix storedMatrix, Matrix source) {
        return specimen.supports(source) && (specimen.form() != Form.EMBEDDED_COALIFIED || storedMatrix == source);
    }
    public static boolean compatiblePair(Specimen stone, Specimen deepslate) {
        return stone.equals(deepslate) && stone.supports(Matrix.STONE) && deepslate.supports(Matrix.DEEPSLATE);
    }
    public static int dropCount(boolean pickaxe, boolean correctForState) { return pickaxe && correctForState ? 1 : 0; }
}
