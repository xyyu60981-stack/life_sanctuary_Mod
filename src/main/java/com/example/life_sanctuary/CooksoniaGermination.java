package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;

/** 配子体单回合提案的出生／枯死处理阶段，不是玩家动作或完整回合入口。 */
public final class CooksoniaGermination {
    private CooksoniaGermination() {}

    /** 普通新植株的关卡基值；冻原植株在此基础上行动减一，不修改分叉和生命。 */
    public record Parameters(int actions, int branches, int initialHealth) {
        public Parameters {
            if (actions < 0 || branches < 0 || initialHealth <= 0)
                throw new IllegalArgumentException("Invalid configured germination resources");
        }
    }

    /**
     * before 是动作开始前快照；afterLanding 已完成该动作的资源、落点、水路与头失活处理。
     * 两者回合号必须相同，且原有配子体尚未推进。返回值仅供统一回合继续结算和提交。
     * 先批量检查并构造全部结果，遇到旧提案／身份冲突时不发布任何一部分。
     */
    public static Optional<CooksoniaGameState> finish(CooksoniaGameState before, CooksoniaGameState afterLanding,
            CooksoniaGametophyteTurns.Plan plan, Parameters parameters) {
        Objects.requireNonNull(before);
        Objects.requireNonNull(afterLanding);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(parameters);
        if (!plan.matches(before) || afterLanding.turn() != before.turn()
                || afterLanding.board() != plan.ground()
                || before.cleared() && !afterLanding.cleared()
                || !afterLanding.gametophytes().equals(before.gametophytes())) return Optional.empty();

        var plants = new HashMap<>(afterLanding.plants());
        var heads = new HashMap<>(afterLanding.heads());
        var remaining = new HashMap<>(plan.advanced());
        var changes = new HashMap<CooksoniaBoard.Pos, CooksoniaOccupancy.Change>();
        for (var gametophyte : plan.advanced().values()) {
            boolean grows = plan.growing().contains(gametophyte.id());
            boolean withers = plan.withering().contains(gametophyte.id());
            if (!grows && !withers) continue;
            var expected = new CooksoniaOccupancy.Entry(gametophyte.id(), gametophyte.futurePlantId(),
                    CooksoniaOccupancy.Kind.GAMETOPHYTE);
            Optional<CooksoniaOccupancy.Entry> replacement = Optional.empty();
            if (grows) {
                int actions = parameters.actions() - (gametophyte.kind() == CooksoniaPlant.Kind.TUNDRA ? 1 : 0);
                if (actions < 0)
                    throw new IllegalArgumentException("Tundra germination requires base actions of at least one");
                int bonus = gametophyte.peatBonus() ? 1 : 0;
                var plant = new CooksoniaPlant(gametophyte.futurePlantId(), gametophyte.kind(),
                        Math.addExact(actions, bonus), Math.addExact(parameters.branches(), bonus), false);
                // 同一棋盘单位从配子体转为头，保留单位 ID；植株使用提前预留的独立 ID。
                var head = CooksoniaHead.seeded(gametophyte.id(), plant.id(), gametophyte.pos(), parameters.initialHealth());
                if (plants.putIfAbsent(plant.id(), plant) != null || heads.putIfAbsent(head.id(), head) != null)
                    return Optional.empty();
                replacement = Optional.of(new CooksoniaOccupancy.Entry(head.id(), plant.id(), CooksoniaOccupancy.Kind.HEAD));
            }
            changes.put(gametophyte.pos(), new CooksoniaOccupancy.Change(expected, replacement));
            remaining.remove(gametophyte.id());
        }
        var occupancy = afterLanding.occupancy().changeAll(changes);
        if (occupancy.isEmpty()) return Optional.empty();
        return Optional.of(new CooksoniaGameState(afterLanding.board(), occupancy.get(), plants, heads, remaining,
                afterLanding.spores(), afterLanding.waits(), afterLanding.pickaxes(), afterLanding.score(),
                plan.nextTurn(), afterLanding.cleared(), afterLanding.producedSpores()));
    }
}
