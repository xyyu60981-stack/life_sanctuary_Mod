package com.example.life_sanctuary;

/** 与普通成长计时分离的活动区块计时快照；失败后保留已到期记录供恢复。 */
public record PlantWitheringTimer(long targetTicks, long elapsedTicks, boolean failed) {
    public PlantWitheringTimer {
        if (targetTicks <= 0 || elapsedTicks < 0 || elapsedTicks > targetTicks)
            throw new IllegalArgumentException("Invalid withering timer");
    }
    public long remainingTicks() { return targetTicks - elapsedTicks; }
}
