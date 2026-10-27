package com.example.life_sanctuary;

import java.util.Optional;
import java.util.UUID;

/** 同株各头共享的资源快照；没有按头复制行动／分叉，不自行推进回合。 */
public record CooksoniaPlant(UUID id, Kind kind, int actions, int branches, boolean retired,
        int stemGrowthTurns, boolean sporeCycleFinished) {
    public enum Kind { COOKSONIA, TUNDRA }

    public CooksoniaPlant(UUID id, Kind kind, int actions, int branches, boolean retired) {
        this(id, kind, actions, branches, retired, 0, false);
    }

    public CooksoniaPlant {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(kind);
        if (actions < 0 || branches < 0 || stemGrowthTurns < 0 || retired && (actions != 0 || branches != 0))
            throw new IllegalArgumentException("Invalid shared Cooksonia resources");
    }

    public Optional<CooksoniaPlant> spendAction() {
        return retired || actions == 0 ? Optional.empty() : Optional.of(new CooksoniaPlant(id, kind, actions - 1, branches, false,
                stemGrowthTurns, sporeCycleFinished));
    }
    public Optional<CooksoniaPlant> spendBranch() {
        return retired || branches == 0 ? Optional.empty() : Optional.of(new CooksoniaPlant(id, kind, actions, branches - 1, false,
                stemGrowthTurns, sporeCycleFinished));
    }

    /** 必须与第一次占领泥炭及固定分数共同提交，不能对同一地格重复调用发奖励。 */
    public Optional<CooksoniaPlant> peatBonus() {
        return retired ? Optional.empty() : Optional.of(new CooksoniaPlant(id, kind,
                Math.addExact(actions, 1), Math.addExact(branches, 1), false, stemGrowthTurns, sporeCycleFinished));
    }

    /** 最后一个头失活时由统一结算调用；资源归零且不能靠泥炭接口重新激活。 */
    public CooksoniaPlant retire() {
        return retired ? this : new CooksoniaPlant(id, kind, 0, 0, true, stemGrowthTurns, sporeCycleFinished);
    }
}
