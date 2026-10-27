package com.example.life_sanctuary;

import java.util.UUID;

/** 配子体独立身份与回合进度；futurePlantId 预留给萌发后的独立植株，不提前复制资源。 */
public record CooksoniaGametophyte(UUID id, UUID futurePlantId, CooksoniaBoard.Pos pos,
        CooksoniaPlant.Kind kind, long bornTurn, int growthTurns, int dryTurns, boolean peatBonus) {
    /** 未领取泥炭奖励的初始／既有记录，兼容此前纯模型构造入口。 */
    public CooksoniaGametophyte(UUID id, UUID futurePlantId, CooksoniaBoard.Pos pos,
            CooksoniaPlant.Kind kind, long bornTurn, int growthTurns, int dryTurns) {
        this(id, futurePlantId, pos, kind, bornTurn, growthTurns, dryTurns, false);
    }
    public CooksoniaGametophyte {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(futurePlantId);
        java.util.Objects.requireNonNull(pos);
        java.util.Objects.requireNonNull(kind);
        if (bornTurn < 0 || growthTurns < 0 || growthTurns > 2 || dryTurns < 0 || dryTurns > 2)
            throw new IllegalArgumentException("Invalid gametophyte turn progress");
    }

    public boolean readyToGrow() { return growthTurns == 2 && dryTurns == 0; }
    public boolean readyToWither() { return dryTurns == 2; }
}
