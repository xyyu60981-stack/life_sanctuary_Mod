package com.example.life_sanctuary;

/**
 * 单槽当前培育阶段的不可变活动计时；由机器调度提供实际活动 tick，不使用世界时间差。
 * 阶段完成只表示可以提交产物／推进事务，不能在读取进度时自动替换物品。
 */
public record CultivationProgress(String stage, long targetTicks, long elapsedTicks, boolean failed) {
    public CultivationProgress {
        if (stage == null || stage.isBlank() || targetTicks <= 0 || elapsedTicks < 0 || elapsedTicks > targetTicks)
            throw new IllegalArgumentException("Invalid cultivation progress");
    }

    /** 自然时长由物种配方选择一次；本方法不随机，不根据轮询或红石边沿重抽。 */
    public static CultivationProgress start(String stage, long selectedNaturalTicks) {
        return new CultivationProgress(stage, CultivationRules.durationFromNatural(selectedNaturalTicks), 0, false);
    }

    public long remainingTicks() { return targetTicks - elapsedTicks; }
    public boolean ready() { return !failed && remainingTicks() == 0; }

    /** 断电及异常暂停，超长有效步长仅饱和当前阶段，不越过中间产物。 */
    public CultivationProgress advance(long activeTicks, boolean powered) {
        if (activeTicks < 0) throw new IllegalArgumentException("Negative cultivation active ticks");
        if (!powered || failed || activeTicks == 0 || elapsedTicks == targetTicks) return this;
        return new CultivationProgress(stage, targetTicks, elapsedTicks + Math.min(activeTicks, remainingTicks()), false);
    }

    /** 不自动恢复可能已经发生库存副作用的失败阶段，也不重置原时长或进度。 */
    public CultivationProgress freezeForRecovery() {
        return failed ? this : new CultivationProgress(stage, targetTicks, elapsedTicks, true);
    }

    /** 常规保存更新只能前进或冻结，不能更换阶段、目标时长或解冻。 */
    public boolean permits(CultivationProgress next) {
        return next != null && stage.equals(next.stage) && targetTicks == next.targetTicks
                && elapsedTicks <= next.elapsedTicks && (!failed || next.failed && elapsedTicks == next.elapsedTicks);
    }
}
