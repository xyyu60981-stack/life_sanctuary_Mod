package com.example.life_sanctuary;

import java.util.Objects;
import java.util.UUID;

/** 只请求某段走茎向侧方分叉；新头身份、生命与资源由服务器决定。 */
public record CooksoniaBranchRequest(UUID sessionId, long revision, UUID stemId, CooksoniaHead.Heading direction) {
    public CooksoniaBranchRequest {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(stemId);
        Objects.requireNonNull(direction);
        if (revision < 0) throw new IllegalArgumentException("Negative Cooksonia branch request revision");
    }
}
