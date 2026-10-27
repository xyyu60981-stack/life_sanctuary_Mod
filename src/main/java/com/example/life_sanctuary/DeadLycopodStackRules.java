package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.util.StringRepresentable;

/** Two decorations form a medium dead lycopod; no living identity or per-tick work. */
public final class DeadLycopodStackRules {
    private DeadLycopodStackRules() {}
    public enum Part implements StringRepresentable {
        SINGLE("single"), LOWER("lower"), UPPER("upper");
        private final String name;
        Part(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }

    /** Null below means a different block, whose substrate suitability is supplied separately. */
    public static Optional<Part> placement(Part below, boolean substrate) {
        if (below == Part.SINGLE) return Optional.of(Part.UPPER);
        return below == null && substrate ? Optional.of(Part.SINGLE) : Optional.empty();
    }

    /** Empty result removes this cell without forwarding the initiating player's tool. */
    public static Optional<Part> update(Part current, Part below, Part above, boolean substrate) {
        if (current == Part.UPPER)
            return below == Part.SINGLE || below == Part.LOWER ? Optional.of(Part.UPPER) : Optional.empty();
        if (below != null || !substrate) return Optional.empty();
        return Optional.of(above == Part.UPPER ? Part.LOWER : Part.SINGLE);
    }

    /** Exactly one for the directly sheared block; no silk-touch or fortune multiplier. */
    public static int dropCount(boolean shears) { return shears ? 1 : 0; }
}
