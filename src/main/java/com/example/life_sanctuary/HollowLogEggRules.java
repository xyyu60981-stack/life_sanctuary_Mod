package com.example.life_sanctuary;

import net.minecraft.util.RandomSource;

/** Pure rules; sampling happens only when an actual operation starts or produces its result. */
public final class HollowLogEggRules {
    private HollowLogEggRules() {}
    public static final int MINIMUM_SOAK_TICKS = 200;
    public static final int MAXIMUM_SOAK_TICKS = 300;
    public static int soakingDuration(RandomSource random) { return 200 + random.nextInt(101); }
    public static int stalkCount(RandomSource random) { return 2 + random.nextInt(3); }
    public static int silverfishCount(boolean eggs, boolean silkTouch, boolean blockDrops, RandomSource random) {
        return eggs && !silkTouch && blockDrops ? 1 + random.nextInt(3) : 0;
    }
    public record Timer(int target, int elapsed) {
        public Timer {
            if (target < MINIMUM_SOAK_TICKS || target > MAXIMUM_SOAK_TICKS || elapsed < 0 || elapsed > target)
                throw new IllegalArgumentException("Invalid egg soaking timer");
        }
        public boolean ready() { return target == elapsed; }
        public Timer advance(long ticks) {
            if (ticks < 0) throw new IllegalArgumentException("Negative active ticks");
            return new Timer(target, elapsed + (int) Math.min(ticks, target - elapsed));
        }
    }
}
