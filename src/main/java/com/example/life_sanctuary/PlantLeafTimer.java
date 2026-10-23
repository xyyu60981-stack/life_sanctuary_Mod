package com.example.life_sanctuary;

import net.minecraft.util.RandomSource;

/** 独立叶计时快照，仅累计调用方明确提供的活动 tick，不使用世界时间戳。 */
public record PlantLeafTimer(Kind kind, long targetTicks, long elapsedTicks, boolean failed) {
    public enum Kind {
        SHEDDING(24_000, 48_000), REGROWTH(48_000, 120_000);
        private final int minimum, maximum;
        Kind(int minimum, int maximum) { this.minimum = minimum; this.maximum = maximum; }
    }

    public PlantLeafTimer {
        java.util.Objects.requireNonNull(kind);
        if (targetTicks < kind.minimum || targetTicks > kind.maximum || elapsedTicks < 0 || elapsedTicks > targetTicks)
            throw new IllegalArgumentException("Invalid independent leaf timer");
    }

    static PlantLeafTimer start(Kind kind, RandomSource random) {
        java.util.Objects.requireNonNull(random);
        return new PlantLeafTimer(kind, kind.minimum + random.nextInt(kind.maximum - kind.minimum + 1), 0, false);
    }

    public long remainingTicks() { return targetTicks - elapsedTicks; }
    public boolean ready() { return !failed && remainingTicks() == 0; }

    /** 负数拒绝，超长步长饱和至目标；异常冻结后不再累计，也不自动清除异常。 */
    public PlantLeafTimer advance(long activeTicks) {
        if (activeTicks < 0) throw new IllegalArgumentException("Negative active ticks");
        if (failed || activeTicks == 0 || elapsedTicks == targetTicks) return this;
        return new PlantLeafTimer(kind, targetTicks, elapsedTicks + Math.min(activeTicks, remainingTicks()), false);
    }

    public PlantLeafTimer freezeForRecovery() {
        return failed ? this : new PlantLeafTimer(kind, targetTicks, elapsedTicks, true);
    }

    boolean permits(PlantLeafTimer next) {
        return kind == next.kind && targetTicks == next.targetTicks && elapsedTicks <= next.elapsedTicks
                && (!failed || next.failed && elapsedTicks == next.elapsedTicks);
    }
}
