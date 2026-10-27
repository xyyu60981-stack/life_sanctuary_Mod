package com.example.life_sanctuary;

import java.util.Objects;
import java.util.UUID;

/** 等待只是一回合意图，不含选中头、推进次数或客户端时间。 */
public record CooksoniaWaitRequest(UUID sessionId, long revision) {
    public CooksoniaWaitRequest {
        Objects.requireNonNull(sessionId);
        if (revision < 0) throw new IllegalArgumentException("Negative Cooksonia wait request revision");
    }
}
