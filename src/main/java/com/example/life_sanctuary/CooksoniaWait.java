package com.example.life_sanctuary;

import java.util.Objects;

/** 等待操作的次数扣除阶段；成长和结果判定必须在随后完整回合中完成。 */
public final class CooksoniaWait {
    private CooksoniaWait() {}
    public enum Status { PREPARED, NO_WAITS }

    public record Result(Status status, CooksoniaGameState state) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(state);
        }
        public boolean prepared() { return status == Status.PREPARED; }
    }

    /**
     * 没有活头也能使用剩余等待次数；不指定某株，不扣行动／生命。
     * 不可对这个中间结果提前判失败：最后一次等待可能恰好让配子体萌发。
     */
    public static Result prepare(CooksoniaGameState source) {
        Objects.requireNonNull(source);
        if (source.waits() == 0) return new Result(Status.NO_WAITS, source);
        var next = new CooksoniaGameState(source.board(), source.occupancy(), source.plants(), source.heads(),
                source.gametophytes(), source.spores(), source.waits() - 1, source.pickaxes(),
                source.score(), source.turn(), source.cleared(), source.producedSpores());
        return new Result(Status.PREPARED, next);
    }
}
