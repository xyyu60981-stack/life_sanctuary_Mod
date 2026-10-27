package com.example.life_sanctuary;

import net.minecraft.resources.ResourceLocation;

/**
 * 可信库存适配器边界：false 必须表示库存完全未变，true 表示恰好完成规定交换。
 * 抛错时库存结果不确定，控制器会冻结批次；不能将模拟操作当成成功提交。
 */
public final class CultivationInventoryExchange {
    private CultivationInventoryExchange() {}
    @FunctionalInterface
    public interface Input {
        /** 验证对应物种标签并消费一袋满孢子袋，不接收空袋或花粉袋。 */
        boolean consumeOneSporeBag(ResourceLocation species);
    }
    @FunctionalInterface
    public interface Output {
        /** 将完整四份逻辑产物映射并交付到合法库存；空间不足不得部分插入后返回 false。 */
        boolean deliver(CultivationBatch.Product product);
        /** 交付已物化的剩余实物；旧适配器默认拒绝，不按逻辑产物重新补足四份。 */
        default boolean deliverStored(java.util.List<net.minecraft.world.item.ItemStack> stacks) { return false; }
    }
}
