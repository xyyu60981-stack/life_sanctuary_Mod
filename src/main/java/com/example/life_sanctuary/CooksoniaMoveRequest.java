package com.example.life_sanctuary;

import java.util.Objects;
import java.util.UUID;

/** 客户端只表达移动方向；目的格、代价及新走茎身份全部由服务器计算。 */
public record CooksoniaMoveRequest(UUID sessionId, long revision, UUID headId, CooksoniaHead.Heading direction) {
    public CooksoniaMoveRequest {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(headId);
        Objects.requireNonNull(direction);
        if (revision < 0) throw new IllegalArgumentException("Negative Cooksonia move request revision");
    }
}
