package com.example.life_sanctuary;

import java.util.Objects;
import java.util.UUID;

/** 客户端操作意图，不携带玩家身份、分数、资源、伤害或整局状态。 */
public record CooksoniaPickaxeRequest(UUID sessionId, long revision, UUID headId, CooksoniaBoard.Pos target) {
    public CooksoniaPickaxeRequest {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(headId);
        Objects.requireNonNull(target);
        if (revision < 0) throw new IllegalArgumentException("Negative Cooksonia request revision");
    }
}
