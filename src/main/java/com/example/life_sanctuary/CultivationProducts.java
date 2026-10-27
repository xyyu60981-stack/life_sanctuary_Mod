package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 配置期逻辑产物映射；只创建交付提案，不写库存、不清除培养批次。 */
public final class CultivationProducts {
    private CultivationProducts() {}
    private record Key(ResourceLocation species, ResourceLocation output) {}
    private static final Map<Key, ItemStack> TEMPLATES = new ConcurrentHashMap<>();

    /** 只查询是否已配置，不复制物品；适合调度前检查，不代表目标库存有容量。 */
    public static boolean isAvailable(ResourceLocation species, ResourceLocation output) {
        java.util.Objects.requireNonNull(species);
        java.util.Objects.requireNonNull(output);
        return PlantSpeciesRegistry.find(species).isPresent() && TEMPLATES.containsKey(new Key(species, output));
    }

    /** 按阶段顺序列出缺失映射，便于未来界面反馈；同一产物只列一次。 */
    public static List<ResourceLocation> missingOutputs(CultivationRecipe recipe) {
        java.util.Objects.requireNonNull(recipe);
        return recipe.phases().stream().map(CultivationRecipe.Phase::output).distinct()
                .filter(output -> !isAvailable(recipe.species(), output)).toList();
    }

    public static boolean isReady(CultivationRecipe recipe) {
        java.util.Objects.requireNonNull(recipe);
        for (var phase : recipe.phases())
            if (!isAvailable(recipe.species(), phase.output())) return false;
        return true;
    }

    /** 一份产物的完整组件模板。按物种隔离，拒绝覆盖；注册后修改原栈不会影响模板。 */
    public static synchronized void register(ResourceLocation species, ResourceLocation output, ItemStack template) {
        java.util.Objects.requireNonNull(species);
        java.util.Objects.requireNonNull(output);
        java.util.Objects.requireNonNull(template);
        if (PlantSpeciesRegistry.find(species).isEmpty())
            throw new IllegalArgumentException("Cultivation product requires a known species");
        if (template.isEmpty() || template.getCount() != 1 || template.getMaxStackSize() < 1)
            throw new IllegalArgumentException("Cultivation product template must contain exactly one item");
        var key = new Key(species, output);
        if (TEMPLATES.containsKey(key))
            throw new IllegalArgumentException("Duplicate cultivation product: " + species + "/" + output);
        TEMPLATES.put(key, template.copy());
    }

    /**
     * 每次返回独立物品副本；不可堆叠产物分成四栈。列表不可改，栈可供库存适配器消费。
     * 缺失映射不猜测默认物品；调用者仍须一次性确认整批容量，再通过输出事务交付。
     */
    public static Optional<List<ItemStack>> resolve(CultivationBatch.Product product) {
        java.util.Objects.requireNonNull(product);
        var template = TEMPLATES.get(new Key(product.species(), product.output()));
        if (template == null) return Optional.empty();
        int limit = template.getMaxStackSize();
        if (limit < 1) throw new IllegalStateException("Invalid cultivation product stack limit");
        var stacks = new ArrayList<ItemStack>();
        for (int remaining = product.count(); remaining > 0;) {
            int count = Math.min(remaining, limit);
            stacks.add(template.copyWithCount(count));
            remaining -= count;
        }
        return Optional.of(List.copyOf(stacks));
    }
}
