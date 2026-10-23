package com.example.life_sanctuary;

import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/** 一次有效苏铁自然受粉判定；由种群窗口调度调用，不扫描世界方块、不处理花粉袋。 */
public final class PlantNaturalPollination {
    private PlantNaturalPollination() {}

    public enum Outcome { REJECTED, WAITING_FOR_POLLEN, FAILED, FERTILIZED, STALE }
    public record Result(Outcome outcome, int donors, int successPercent, int reservedSeeds) {}

    /** 物种提供种子上下限；第一株雄株从最低产量起，每多一株提高随机上限 2 粒。 */
    public record SeedYield(int minimum, int maximum) {
        public SeedYield {
            if (minimum <= 0 || maximum < minimum) throw new IllegalArgumentException("Invalid seed yield range");
        }

        int sample(int donors, RandomSource random) {
            if (donors <= 0) throw new IllegalArgumentException("Seed yield requires pollen donors");
            int upper = (int) Math.min((long) maximum, minimum + 2L * (donors - 1));
            return minimum == upper ? minimum : minimum + random.nextInt(upper - minimum + 1);
        }
    }

    /**
     * 调用者须已为受粉株开启指定窗口，并确认本次到达物种规定的判定时刻。
     * 无合格供粉株只是等待，不投概率也不登记失败。只有实际失败才关闭本轮受粉侧；
     * 失败完全花继续供粉。成功保留一次抽定的种子数，进入受精状态等待独立成熟。
     */
    public static Result attemptCycad(ServerLevel level, PlantIndividuals store, PlantIndividualState expected,
            String flowerKey, long roundId, Predicate<PlantIndividualState> eligibleDonor,
            SeedYield yield, RandomSource random) {
        Objects.requireNonNull(eligibleDonor);
        Objects.requireNonNull(yield);
        Objects.requireNonNull(random);
        if (store != PlantIndividuals.get(level)) throw new IllegalArgumentException("Wrong dimension store");
        var round = store.flowerRound(expected.id()).orElse(null);
        if (round == null || round.id() != roundId || !round.allowsPollenReception()
                || !PlantPollination.canReceivePollen(store, expected, flowerKey)) return result(Outcome.REJECTED, 0, 0, 0);
        var flower = store.flower(expected.id(), flowerKey).orElseThrow();
        if (!PlantPollination.available(level, expected.base()) || !PlantPollination.available(level, flower.position()))
            return result(Outcome.REJECTED, 0, 0, 0);
        int donors = PlantPollination.nearbyCycadMales(level, store, expected, flowerKey, eligibleDonor).size();
        if (!matches(store, expected, flowerKey, flower, round)) return result(Outcome.STALE, donors, 0, 0);
        if (donors == 0) return result(Outcome.WAITING_FOR_POLLEN, 0, 0, 0);

        int chance = PlantPollination.cycadSuccessPercent(donors);
        // 100% 时无需抽随机数；不因任务重试给失败个体重复投骰子。
        boolean success = chance == 100 || random.nextInt(100) < chance;
        if (!matches(store, expected, flowerKey, flower, round)) return result(Outcome.STALE, donors, chance, 0);
        if (!success)
            return result(store.failFlowerRound(expected, roundId, flowerKey) ? Outcome.FAILED : Outcome.STALE,
                    donors, chance, 0);

        int seeds = yield.sample(donors, random);
        if (!matches(store, expected, flowerKey, flower, round)) return result(Outcome.STALE, donors, chance, 0);
        boolean changed = store.replaceFlower(expected, flowerKey, flower,
                flower.withPhase(PlantFlowerState.Phase.FERTILIZED, seeds));
        return result(changed ? Outcome.FERTILIZED : Outcome.STALE, donors, chance, changed ? seeds : 0);
    }

    private static boolean matches(PlantIndividuals store, PlantIndividualState expected, String key,
            PlantFlowerState flower, PlantFlowerRound round) {
        return expected.equals(store.find(expected.id()).orElse(null))
                && store.flowerRound(expected.id()).filter(round::equals).isPresent()
                && store.flower(expected.id(), key).filter(flower::equals).isPresent()
                && PlantPollination.canReceivePollen(store, expected, key);
    }

    private static Result result(Outcome outcome, int donors, int chance, int seeds) {
        return new Result(outcome, donors, chance, seeds);
    }
}
