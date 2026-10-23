package com.example.life_sanctuary;

/** 培养柜共同数值；不推断物种有没有配子体／幼苗，不代替特殊物种配方。 */
public final class CultivationRules {
    private CultivationRules() {}
    public static final int SLOT_COUNT = 4;
    public static final int OUTPUT_PER_BAG = 4;
    public static final int LIGHT_LEVEL = 10;

    /** 自然耗时的 60%，不足一刻向上取整；整数分段计算避免溢出或浮点误差。 */
    public static long durationFromNatural(long naturalTicks) {
        if (naturalTicks <= 0) throw new IllegalArgumentException("Positive natural cultivation duration required");
        return naturalTicks / 5 * 3 + (naturalTicks % 5 * 3 + 4) / 5;
    }

    /** 只统计最终完成槽，中间阶段成熟不计入：0、4、8、12、15。 */
    public static int comparatorSignal(int completedSlots) {
        if (completedSlots < 0 || completedSlots > SLOT_COUNT)
            throw new IllegalArgumentException("Invalid completed cultivation slot count");
        return Math.min(15, completedSlots * 4);
    }
}
