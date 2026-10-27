package com.example.life_sanctuary;

import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;

/** Explicit material kind, never inferred from model collision or item names. */
public final class CharredMaterialRules {
    private CharredMaterialRules() {}
    public enum Kind implements StringRepresentable {
        FULL(4, 14, 19200, 28800), STEM(1, 10, 4800, 7200);
        public final int charcoal, light, minimumBurnTicks, maximumBurnTicks;
        Kind(int charcoal, int light, int minimumBurnTicks, int maximumBurnTicks) {
            this.charcoal = charcoal; this.light = light;
            this.minimumBurnTicks = minimumBurnTicks; this.maximumBurnTicks = maximumBurnTicks;
        }
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public enum Phase { PROBING, IGNITION, BURNING }
    public static boolean heatSource(boolean magma, boolean fire, boolean lava, Kind charred, boolean lit) {
        return magma || fire || lava || charred == Kind.FULL && lit;
    }
    public static int charcoalCount(Kind kind, boolean silk) { return silk ? 0 : kind.charcoal; }
    public static int boneMealCount(Kind kind, boolean silk) { return silk ? 0 : kind.charcoal; }
    public record Cycle(Kind kind, Phase phase, int target, int elapsed, boolean failed) {
        public Cycle {
            java.util.Objects.requireNonNull(kind); java.util.Objects.requireNonNull(phase);
            int minimum = phase == Phase.PROBING ? 100 : phase == Phase.IGNITION ? 200 : kind.minimumBurnTicks;
            int maximum = phase == Phase.PROBING ? 100 : phase == Phase.IGNITION ? 300 : kind.maximumBurnTicks;
            if (target < minimum || target > maximum || elapsed < 0 || elapsed > target)
                throw new IllegalArgumentException("Invalid charred material timer");
        }
        public static Cycle start(Kind kind, Phase phase, RandomSource random) {
            if (phase == Phase.PROBING) return new Cycle(kind, phase, 100, 0, false);
            int minimum = phase == Phase.IGNITION ? 200 : kind.minimumBurnTicks;
            int maximum = phase == Phase.IGNITION ? 300 : kind.maximumBurnTicks;
            return new Cycle(kind, phase, minimum + random.nextInt(maximum - minimum + 1), 0, false);
        }
        public Cycle advance(long ticks) {
            if (ticks < 0) throw new IllegalArgumentException("Negative active ticks");
            return failed ? this : new Cycle(kind, phase, target, elapsed + (int) Math.min(ticks, target - elapsed), false);
        }
        public boolean ready() { return !failed && target == elapsed; }
        public Cycle fail() { return new Cycle(kind, phase, target, elapsed, true); }
    }
}
