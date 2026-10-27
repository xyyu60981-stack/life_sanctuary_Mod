package com.example.life_sanctuary;

import java.util.Objects;
import java.util.Optional;

/** 单回合组合：原有配子体进度与出生／枯死、原有走茎的同株成熟；结果判定在宿主最后进行。 */
public final class CooksoniaTurnResolver {
    private CooksoniaTurnResolver() {}

    public record Rules(CooksoniaGermination.Parameters births, CooksoniaStemMaturation.Rules stems) {
        public Rules {
            Objects.requireNonNull(births);
            Objects.requireNonNull(stems);
        }
    }

    /** afterAction 的行动／水路已经完成，不能预先推进回合或配子体进度。 */
    public static Optional<CooksoniaGameState> finish(CooksoniaGameState before, CooksoniaGameState afterAction, Rules rules) {
        Objects.requireNonNull(before);
        Objects.requireNonNull(afterAction);
        Objects.requireNonNull(rules);
        if (afterAction.producedSpores() != before.producedSpores()) return Optional.empty();
        var plan = CooksoniaGametophyteTurns.plan(before, afterAction.board());
        var born = CooksoniaGermination.finish(before, afterAction, plan, rules.births());
        if (born.isEmpty()) return Optional.empty();
        return CooksoniaStemMaturation.finish(before, born.get(), rules.stems());
    }

    /** 等待无需选头；本次次数扣除与整个回合只构造提案，绝不发布中间状态。 */
    public static Optional<CooksoniaGameState> waitTurn(CooksoniaGameState before, Rules rules) {
        var waiting = CooksoniaWait.prepare(before);
        return waiting.prepared() ? finish(before, waiting.state(), rules) : Optional.empty();
    }
}
