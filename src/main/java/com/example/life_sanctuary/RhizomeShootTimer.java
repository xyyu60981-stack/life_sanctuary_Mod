package com.example.life_sanctuary;

/** 两个游戏日的补生等待，保存活动区块累计 tick，和幼丛自身的成长计时分开。 */
public record RhizomeShootTimer(long elapsedTicks) {
    public static final long TARGET_TICKS = 48_000;
    public RhizomeShootTimer {
        if (elapsedTicks < 0 || elapsedTicks > TARGET_TICKS) throw new IllegalArgumentException("Invalid shoot timer");
    }
    public boolean ready() { return elapsedTicks == TARGET_TICKS; }
}
