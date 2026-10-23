package com.example.life_sanctuary;

import java.util.OptionalInt;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** 共享生殖状态操作；形态更新、到期调度、种子掉落与权限由物种适配器协调。 */
public final class PlantPollination {
    private PlantPollination() {}

    /** 本轮仍在开花；受粉资格须另查 canReceivePollen，完全花失败后仍可供粉。 */
    public static boolean isFlowering(PlantIndividuals store, PlantIndividualState expected, String key) {
        return active(store, expected) && store.flowerRoundActive(expected.id())
                && store.flower(expected.id(), key).filter(flower -> flower.phase() == PlantFlowerState.Phase.FLOWERING)
                        .filter(flower -> attached(store, expected, flower)).isPresent();
    }

    public static boolean canReceivePollen(PlantIndividuals store, PlantIndividualState expected, String key) {
        return active(store, expected) && store.acceptsPollenInRound(expected.id())
                && store.flower(expected.id(), key).filter(PlantFlowerState::receivesPollen)
                        .filter(flower -> attached(store, expected, flower)).isPresent();
    }

    /**
     * 以雌株基底为中心查询 XYZ 21×9×21 内同种供粉个体，按 UUID 去重，不加载区块。
     * eligibleMale 由物种限定活动母株/繁殖窗口等资格，必须只读；本层不替物种开启自然繁殖。
     */
    public static Set<UUID> nearbyCycadMales(ServerLevel level, PlantIndividuals store,
            PlantIndividualState expected, String flowerKey, Predicate<PlantIndividualState> eligibleMale) {
        java.util.Objects.requireNonNull(eligibleMale);
        if (store != PlantIndividuals.get(level)) throw new IllegalArgumentException("Wrong dimension store");
        if (!canReceivePollen(store, expected, flowerKey)
                || !available(level, expected.base())) return Set.of();
        var female = store.flower(expected.id(), flowerKey).orElse(null);
        if (female == null || !female.receivesPollen() || !attached(store, expected, female)
                || !available(level, female.position())) return Set.of();
        Set<UUID> result = new HashSet<>();
        for (UUID id : store.cycadPollenCandidates(expected.species(), expected.base())) {
            if (id.equals(expected.id())) continue;
            var male = store.find(id).orElse(null);
            if (male != null && active(store, male) && available(level, male.base())
                    && store.hasPollen(id, pos -> available(level, pos)) && eligibleMale.test(male)) result.add(id);
        }
        return Set.copyOf(result);
    }

    /** 已确认的三种雌雄异株苏铁规则；不自动推广到其他种子植物。 */
    public static int cycadSuccessPercent(int males) {
        if (males < 0) throw new IllegalArgumentException("Negative male count");
        if (males == 0) return 0;
        return males >= 4 ? 100 : 40 + (males - 1) * 20;
    }

    static boolean available(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                && level.getChunkSource().isPositionTicking(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    public static boolean canCollectPollen(PlantIndividuals store, PlantIndividualState expected, String key) {
        return active(store, expected) && store.flowerRoundActive(expected.id()) && store.flower(expected.id(), key)
                .filter(PlantFlowerState::providesPollen).filter(flower -> attached(store, expected, flower)).isPresent();
    }

    /** 物种先计算种子数；固定补种型可直接结种，其余进入受精阶段等待独立成熟程序。 */
    public static ReproductiveBagUseResult pollinate(PlantIndividuals store, PlantIndividualState expected,
            String key, ResourceLocation pollenSpecies, int seedCount, boolean immediatelyReady) {
        if (seedCount <= 0) throw new IllegalArgumentException("Pollination needs a positive seed yield");
        if (!canReceivePollen(store, expected, key)
                || !expected.species().equals(pollenSpecies)) return ReproductiveBagUseResult.rejected();
        var flower = store.flower(expected.id(), key).orElse(null);
        if (flower == null || !flower.receivesPollen() || !attached(store, expected, flower))
            return ReproductiveBagUseResult.rejected();
        var phase = immediatelyReady ? PlantFlowerState.Phase.SEEDS_READY : PlantFlowerState.Phase.FERTILIZED;
        return store.replaceFlower(expected, key, flower, flower.withPhase(phase, seedCount))
                ? ReproductiveBagUseResult.consumed(true) : ReproductiveBagUseResult.rejected();
    }

    /** 只改变生殖状态；调用者必须确认独立成熟计时已到期，不能每 tick 直接调用。 */
    public static boolean matureSeeds(PlantIndividuals store, PlantIndividualState expected, String key) {
        if (!active(store, expected)) return false;
        var flower = store.flower(expected.id(), key).orElse(null);
        return flower != null && flower.phase() == PlantFlowerState.Phase.FERTILIZED
                && attached(store, expected, flower) && store.replaceFlower(expected, key, flower,
                        flower.withPhase(PlantFlowerState.Phase.SEEDS_READY, flower.seeds()));
    }

    /** 成熟种子不因剪止/气候暂停而补充或消失；成功取走后才向物种返回可结算数量。 */
    public static OptionalInt takeSeeds(PlantIndividuals store, PlantIndividualState expected, String key) {
        if (store.isReadOnly() || !expected.equals(store.find(expected.id()).orElse(null))) return OptionalInt.empty();
        var flower = store.flower(expected.id(), key).orElse(null);
        if (flower == null || flower.phase() != PlantFlowerState.Phase.SEEDS_READY || !attached(store, expected, flower))
            return OptionalInt.empty();
        return store.replaceFlower(expected, key, flower, flower.withPhase(PlantFlowerState.Phase.CLOSED, 0))
                ? OptionalInt.of(flower.seeds()) : OptionalInt.empty();
    }

    private static boolean active(PlantIndividuals store, PlantIndividualState expected) {
        return !store.isReadOnly() && !expected.pruned() && expected.environmentReasons().isEmpty()
                && expected.equals(store.find(expected.id()).orElse(null));
    }

    private static boolean attached(PlantIndividuals store, PlantIndividualState expected, PlantFlowerState flower) {
        return store.partRole(expected.id(), flower.position()).filter(flower.role()::equals).isPresent();
    }
}
