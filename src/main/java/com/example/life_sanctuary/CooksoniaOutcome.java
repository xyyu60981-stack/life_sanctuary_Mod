package com.example.life_sanctuary;

import java.util.Objects;

/** 在落点、成长、新生和死亡全部结算之后调用；无奖励发放、动画或持久化副作用。 */
public final class CooksoniaOutcome {
    private CooksoniaOutcome() {}

    public enum Status {
        IN_PROGRESS, CLEARED_PLAYING, FAILED, CLEARED_ENDED;

        public boolean cleared() { return this == CLEARED_PLAYING || this == CLEARED_ENDED; }
        public boolean ended() { return this == FAILED || this == CLEARED_ENDED; }
    }

    /**
     * state 已保留通关标记；外层提交该快照后再处理首次通关事件。
     * newlyCleared 只描述本局首次跨过门槛，不等于本轮奖励资格。
     */
    public record Result(CooksoniaGameState state, Status status, boolean newlyCleared) {
        public Result {
            Objects.requireNonNull(state);
            Objects.requireNonNull(status);
            if (state.cleared() != status.cleared() || newlyCleared && !state.cleared())
                throw new IllegalArgumentException("Cooksonia outcome and clear flag disagree");
        }
    }

    /** 门槛由正式关卡提供；使用严格大于，避免等于目标时提前通关或加一比较溢出。 */
    public static Result evaluate(CooksoniaGameState state, long threshold) {
        Objects.requireNonNull(state);
        if (threshold < 0) throw new IllegalArgumentException("Negative Cooksonia clear threshold");
        boolean newlyCleared = !state.cleared() && state.score() > threshold;
        var resolved = state;
        // 先固定通关，再检查全灭；之后的死亡不会撤回已经获得的通关。
        if (newlyCleared) {
            resolved = new CooksoniaGameState(state.board(), state.occupancy(), state.plants(), state.heads(),
                    state.gametophytes(), state.spores(), state.waits(), state.pickaxes(),
                    state.score(), state.turn(), true, state.producedSpores());
        }
        boolean anyLiveHead = false;
        for (var head : resolved.heads().values()) {
            if (head.alive()) {
                anyLiveHead = true;
                break;
            }
        }
        // 孢子库存或尚未长成的配子体不能替代等待次数，不按真实时间自动萌发。
        boolean ended = !anyLiveHead && resolved.waits() == 0;
        var status = resolved.cleared()
                ? (ended ? Status.CLEARED_ENDED : Status.CLEARED_PLAYING)
                : (ended ? Status.FAILED : Status.IN_PROGRESS);
        return new Result(resolved, status, newlyCleared);
    }
}
