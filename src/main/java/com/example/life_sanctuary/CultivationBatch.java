package com.example.life_sanctuary;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/**
 * 一袋孢子的单槽批次快照。配方完整保留在批次中，不在每次查询时套用注册表新默认值。
 * 仅构建状态提案，不消费输入、不生成物品；最终库存写入须核对预期批次并原子提交。
 */
public record CultivationBatch(UUID id, CultivationRecipe recipe, int phaseIndex, CultivationProgress progress) {
    public CultivationBatch {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(recipe);
        java.util.Objects.requireNonNull(progress);
        if (phaseIndex < 0 || phaseIndex >= recipe.phases().size() || !recipe.phases().get(phaseIndex).accepts(progress))
            throw new IllegalArgumentException("Cultivation batch phase and progress disagree");
    }

    /** 调用方只在一袋输入确认提交时分配批次 ID 并创建一次，不因断电或轮询重新开始。 */
    public static CultivationBatch start(UUID id, CultivationRecipe recipe, RandomSource random) {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(recipe);
        return new CultivationBatch(id, recipe, 0, recipe.first().start(random));
    }

    public CultivationRecipe.Phase phase() { return recipe.phases().get(phaseIndex); }
    public boolean finalReady() { return phaseIndex == recipe.phases().size() - 1 && progress.ready(); }

    public CultivationBatch advance(long activeTicks, boolean powered) {
        var next = progress.advance(activeTicks, powered);
        return next == progress ? this : new CultivationBatch(id, recipe, phaseIndex, next);
    }

    /** 到期中间阶段才允许提出下一段，身份和配方不变，不对四份中间产物再次乘四。 */
    public Optional<CultivationBatch> nextPhase(RandomSource random) {
        if (!progress.ready() || finalReady()) return Optional.empty();
        int nextIndex = phaseIndex + 1;
        return Optional.of(new CultivationBatch(id, recipe, nextIndex, recipe.phases().get(nextIndex).start(random)));
    }

    public CultivationBatch freezeForRecovery() {
        var frozen = progress.freezeForRecovery();
        return frozen == progress ? this : new CultivationBatch(id, recipe, phaseIndex, frozen);
    }

    /** 当前可手工取出的逻辑产物；读取不等于取走，不应据此直接给予玩家物品。 */
    public record Product(ResourceLocation species, String phase, ResourceLocation output, int count, boolean finalProduct) {
        public Product {
            java.util.Objects.requireNonNull(species);
            java.util.Objects.requireNonNull(output);
            if (phase == null || phase.isBlank() || count != CultivationRules.OUTPUT_PER_BAG)
                throw new IllegalArgumentException("Invalid cultivation batch product");
        }
    }

    public Optional<Product> manualProduct() {
        if (progress.failed()) return Optional.empty();
        // 下一段尚未成熟时，上一段的四份中间产物仍可手工取出并终止该批次。
        int outputIndex = progress.ready() ? phaseIndex : phaseIndex - 1;
        if (outputIndex < 0) return Optional.empty();
        var outputPhase = recipe.phases().get(outputIndex);
        return Optional.of(new Product(recipe.species(), outputPhase.key(), outputPhase.output(),
                outputPhase.outputCount(), outputIndex == recipe.phases().size() - 1));
    }

    /** 自动化不抽取仍在继续培育的中间产物。 */
    public Optional<Product> automatedProduct() { return finalReady() ? manualProduct() : Optional.empty(); }

    /** 普通进度更新不能换配方、换批次、跳阶段或回退；下一阶段由专用事务提交。 */
    public boolean permitsProgress(CultivationBatch next) {
        return next != null && id.equals(next.id) && recipe.equals(next.recipe) && phaseIndex == next.phaseIndex
                && progress.permits(next.progress);
    }
}
