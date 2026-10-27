package com.example.life_sanctuary;

import java.util.Objects;
import java.util.UUID;

/** 播种意图只含既有对局／头身份与目标，不让客户端指定新单位或新植株 UUID。 */
public record CooksoniaSporeRequest(UUID sessionId, long revision, UUID headId, CooksoniaBoard.Pos target) {
    public CooksoniaSporeRequest {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(headId);
        Objects.requireNonNull(target);
        if (revision < 0) throw new IllegalArgumentException("Negative Cooksonia spore request revision");
    }
}
