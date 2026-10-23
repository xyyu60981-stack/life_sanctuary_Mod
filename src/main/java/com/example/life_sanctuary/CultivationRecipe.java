package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/** 显式培育阶段链；输出标识由后续物品适配器解释，不根据植物阶段名猜测物品。 */
public record CultivationRecipe(ResourceLocation id, ResourceLocation species, List<Phase> phases) {
    public record Phase(String key, ResourceLocation output, int minimumNaturalTicks, int maximumNaturalTicks) {
        public Phase {
            java.util.Objects.requireNonNull(output);
            if (key == null || key.isBlank() || minimumNaturalTicks <= 0 || maximumNaturalTicks < minimumNaturalTicks)
                throw new IllegalArgumentException("Invalid cultivation recipe phase");
        }

        /** 只在阶段库存事务确认可以开始后调用一次，固定时长不消费随机数。 */
        public CultivationProgress start(RandomSource random) {
            java.util.Objects.requireNonNull(random);
            int naturalTicks = minimumNaturalTicks == maximumNaturalTicks ? minimumNaturalTicks
                    : minimumNaturalTicks + random.nextInt(maximumNaturalTicks - minimumNaturalTicks + 1);
            return CultivationProgress.start(key, naturalTicks);
        }

        /** 用于未来载入及事务检查，不重抽时长、不推进进度。 */
        public boolean accepts(CultivationProgress progress) {
            return progress != null && key.equals(progress.stage())
                    && progress.targetTicks() >= CultivationRules.durationFromNatural(minimumNaturalTicks)
                    && progress.targetTicks() <= CultivationRules.durationFromNatural(maximumNaturalTicks);
        }

        /** 中间转下一段仍为同一袋的四份，不能按现有四份再乘四。 */
        public int outputCount() { return CultivationRules.OUTPUT_PER_BAG; }
    }

    public CultivationRecipe {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(species);
        phases = List.copyOf(phases);
        if (phases.isEmpty()) throw new IllegalArgumentException("Empty cultivation recipe");
        var keys = new HashSet<String>();
        for (var phase : phases)
            if (!keys.add(phase.key())) throw new IllegalArgumentException("Duplicate cultivation phase key");
    }

    public Phase first() { return phases.getFirst(); }

    public Optional<Phase> phase(String key) { return phases.stream().filter(phase -> phase.key().equals(key)).findFirst(); }

    public Optional<Phase> next(String key) {
        for (int i = 0; i < phases.size(); i++)
            if (phases.get(i).key().equals(key))
                return i + 1 < phases.size() ? Optional.of(phases.get(i + 1)) : Optional.empty();
        throw new IllegalArgumentException("Unknown cultivation phase: " + key);
    }

    public boolean isFinal(String key) {
        if (phase(key).isEmpty()) throw new IllegalArgumentException("Unknown cultivation phase: " + key);
        return phases.getLast().key().equals(key);
    }
}
