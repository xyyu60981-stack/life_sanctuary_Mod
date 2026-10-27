package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;

/** 同株共享、当前只产一次；只认动作开始前已有的走茎，不按格数或头数倍增。 */
public final class CooksoniaStemMaturation {
    private CooksoniaStemMaturation() {}

    /** 关卡显式提供 y、一次产量和累计 z，不设置默认关卡值。 */
    public record Rules(int maturityTurns, int sporesPerMaturity, int cumulativeCap) {
        public Rules {
            if (maturityTurns <= 0 || sporesPerMaturity <= 0 || cumulativeCap < 0)
                throw new IllegalArgumentException("Invalid Cooksonia stem maturation rules");
        }
    }

    /** afterBirth 已推进一个回合并处理出生／枯死，原植株成熟字段尚未推进。 */
    public static Optional<CooksoniaGameState> finish(CooksoniaGameState before, CooksoniaGameState afterBirth, Rules rules) {
        Objects.requireNonNull(before);
        Objects.requireNonNull(afterBirth);
        Objects.requireNonNull(rules);
        if (afterBirth.turn() != Math.addExact(before.turn(), 1L)
                || afterBirth.producedSpores() != before.producedSpores()) return Optional.empty();
        if (before.producedSpores() > rules.cumulativeCap())
            throw new IllegalArgumentException("Existing production exceeds this level's cumulative spore cap");
        for (var original : before.plants().values()) {
            var current = afterBirth.plants().get(original.id());
            if (current == null || current.stemGrowthTurns() != original.stemGrowthTurns()
                    || current.sporeCycleFinished() != original.sporeCycleFinished()) return Optional.empty();
        }
        var plants = new HashMap<>(afterBirth.plants());
        int inventory = afterBirth.spores();
        int produced = before.producedSpores();
        boolean changed = false;
        for (var id : before.occupancy().stemPlants()) {
            var plant = plants.get(id);
            if (plant.sporeCycleFinished() || plant.retired()) continue;
            int growth = plant.stemGrowthTurns() < rules.maturityTurns() ? plant.stemGrowthTurns() + 1 : plant.stemGrowthTurns();
            boolean finished = growth >= rules.maturityTurns();
            if (finished) {
                int emitted = Math.min(rules.sporesPerMaturity(), rules.cumulativeCap() - produced);
                inventory = Math.addExact(inventory, emitted);
                produced = Math.addExact(produced, emitted);
            }
            plants.put(id, new CooksoniaPlant(plant.id(), plant.kind(), plant.actions(), plant.branches(),
                    plant.retired(), growth, finished));
            changed = true;
        }
        if (!changed) return Optional.of(afterBirth);
        return Optional.of(new CooksoniaGameState(afterBirth.board(), afterBirth.occupancy(), plants, afterBirth.heads(),
                afterBirth.gametophytes(), inventory, afterBirth.waits(), afterBirth.pickaxes(), afterBirth.score(),
                afterBirth.turn(), afterBirth.cleared(), produced));
    }
}
