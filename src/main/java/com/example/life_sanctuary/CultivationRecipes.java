package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/** 配置期注册的物种培育方案；缺失时不接收该物种，不回退为其他物种或虚构阶段。 */
public final class CultivationRecipes {
    private CultivationRecipes() {}
    private static final Map<ResourceLocation, CultivationRecipe> BY_SPECIES = new ConcurrentHashMap<>();

    /** 物种定义必须先存在；配置期同一物种只接收一个确定方案，不支持运行时覆盖。 */
    public static synchronized void register(CultivationRecipe recipe) {
        java.util.Objects.requireNonNull(recipe);
        if (PlantSpeciesRegistry.find(recipe.species()).isEmpty())
            throw new IllegalArgumentException("Cultivation recipe requires a known species");
        if (BY_SPECIES.containsKey(recipe.species()) || BY_SPECIES.values().stream().anyMatch(old -> old.id().equals(recipe.id())))
            throw new IllegalArgumentException("Duplicate cultivation recipe or species: " + recipe.id());
        BY_SPECIES.put(recipe.species(), recipe);
    }

    public static Optional<CultivationRecipe> find(ResourceLocation species) {
        return Optional.ofNullable(BY_SPECIES.get(species));
    }

    /** 统一解析旧无命名空间标签和完整 ID；只找方案，就绪检查由具体入口完成。 */
    public static Optional<CultivationRecipe> forSporeBag(net.minecraft.world.item.ItemStack stack) {
        if (!ReproductiveBagKind.SPORE.isFilledBag(stack) || !SporeBagContents.cultivatable(stack)) return Optional.empty();
        String label = SporeBagItem.species(stack);
        var species = ResourceLocation.tryParse(label.contains(":") ? label : LifeSanctuaryMod.MODID + ":" + label);
        return species == null ? Optional.empty() : find(species);
    }

    public static Map<ResourceLocation, CultivationRecipe> snapshot() { return Map.copyOf(BY_SPECIES); }
}
