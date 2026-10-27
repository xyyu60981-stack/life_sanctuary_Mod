package com.example.life_sanctuary;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 由服务器线程持有的临时对局。只接受玩家操作意图，由服务器自己计算新状态。
 * 尚未注册网络／tick／死亡事件；退出或死亡时，未来宿主须调用 close 释放棋盘。
 */
public final class CooksoniaSession {
    public static final int ACTION_LOCK_TICKS = 30;

    public enum ActionStatus {
        APPLIED, WRONG_PLAYER, WRONG_SESSION, CLOSED, ENDED, FLOWING, COOLDOWN, STALE_STATE, INVALID_ACTION, WATER_TIMING_REQUIRED, TURN_RULES_REQUIRED, TOOL_LOCKED
    }

    /** 首次通关事件仅在成功提交的操作返回一次；奖励资格仍由未来玩家进度服务处理。 */
    public record ActionResult(ActionStatus status, Optional<CooksoniaPickaxe.Status> toolStatus, boolean newlyCleared, long revision) {
        public ActionResult {
            Objects.requireNonNull(status);
            Objects.requireNonNull(toolStatus);
            if (revision < 0 || newlyCleared && status != ActionStatus.APPLIED)
                throw new IllegalArgumentException("Invalid Cooksonia action result");
        }
    }

    public record SporeActionResult(ActionStatus status, Optional<CooksoniaSporePreflight.Status> rejection,
            boolean newlyCleared, long revision) {
        public SporeActionResult {
            Objects.requireNonNull(status);
            Objects.requireNonNull(rejection);
            if (revision < 0 || newlyCleared && status != ActionStatus.APPLIED
                    || rejection.isPresent() && status != ActionStatus.INVALID_ACTION
                    || rejection.filter(value -> value == CooksoniaSporePreflight.Status.ALLOWED).isPresent())
                throw new IllegalArgumentException("Invalid Cooksonia spore action result");
        }
    }

    public record WaitActionResult(ActionStatus status, Optional<CooksoniaWait.Status> rejection,
            boolean newlyCleared, long revision) {
        public WaitActionResult {
            Objects.requireNonNull(status);
            Objects.requireNonNull(rejection);
            if (revision < 0 || newlyCleared && status != ActionStatus.APPLIED
                    || rejection.isPresent() && (status != ActionStatus.INVALID_ACTION || rejection.get() != CooksoniaWait.Status.NO_WAITS))
                throw new IllegalArgumentException("Invalid Cooksonia wait action result");
        }
    }

    public record MoveActionResult(ActionStatus status, Optional<CooksoniaMovePreflight.Status> rejection,
            boolean newlyCleared, long revision) {
        public MoveActionResult {
            Objects.requireNonNull(status);
            Objects.requireNonNull(rejection);
            if (revision < 0 || newlyCleared && status != ActionStatus.APPLIED
                    || rejection.isPresent() && status != ActionStatus.INVALID_ACTION
                    || rejection.filter(value -> value == CooksoniaMovePreflight.Status.GROUND_MOVE).isPresent())
                throw new IllegalArgumentException("Invalid Cooksonia move action result");
        }
    }

    public record BranchActionResult(ActionStatus status, Optional<CooksoniaBranchPreflight.Status> rejection,
            boolean newlyCleared, long revision) {
        public BranchActionResult {
            Objects.requireNonNull(status);
            Objects.requireNonNull(rejection);
            if (revision < 0 || newlyCleared && status != ActionStatus.APPLIED
                    || rejection.isPresent() && status != ActionStatus.INVALID_ACTION
                    || rejection.filter(value -> value == CooksoniaBranchPreflight.Status.ALLOWED).isPresent())
                throw new IllegalArgumentException("Invalid Cooksonia branch action result");
        }
    }

    private final Thread owningThread;
    private final UUID playerId;
    private final UUID sessionId;
    private final long threshold;
    private final Optional<CooksoniaWaterPlayback.Timing> waterTiming;
    private final Optional<CooksoniaTurnResolver.Rules> turnRules;
    private CooksoniaGameState state;
    private CooksoniaOutcome.Status outcome;
    private CooksoniaView.Content cachedContent;
    private CooksoniaWaterPlayback waterPlayback;
    private int cooldown;
    private long revision;
    private long lastServerTick = -1;
    // 独立纯模型可直接验证操作；正式玩家会话由Campaign安装真实工具解锁规则。
    private java.util.function.Predicate<CooksoniaToolRules.Tool> toolAccess = tool -> true;

    /** sessionId 由服务器创建，不能直接采用客户端声称的对局身份。 */
    public CooksoniaSession(UUID playerId, UUID sessionId, CooksoniaGameState initial, long threshold) {
        this(playerId, sessionId, initial, threshold, Optional.empty(), Optional.empty());
    }

    public CooksoniaSession(UUID playerId, UUID sessionId, CooksoniaGameState initial, long threshold,
            CooksoniaWaterPlayback.Timing waterTiming) {
        this(playerId, sessionId, initial, threshold, Optional.of(Objects.requireNonNull(waterTiming)), Optional.empty());
    }

    public CooksoniaSession(UUID playerId, UUID sessionId, CooksoniaGameState initial, long threshold,
            CooksoniaTurnResolver.Rules turnRules) {
        this(playerId, sessionId, initial, threshold, Optional.empty(), Optional.of(Objects.requireNonNull(turnRules)));
    }

    public CooksoniaSession(UUID playerId, UUID sessionId, CooksoniaGameState initial, long threshold,
            CooksoniaWaterPlayback.Timing waterTiming, CooksoniaTurnResolver.Rules turnRules) {
        this(playerId, sessionId, initial, threshold, Optional.of(Objects.requireNonNull(waterTiming)), Optional.of(Objects.requireNonNull(turnRules)));
    }

    private CooksoniaSession(UUID playerId, UUID sessionId, CooksoniaGameState initial, long threshold,
            Optional<CooksoniaWaterPlayback.Timing> waterTiming, Optional<CooksoniaTurnResolver.Rules> turnRules) {
        this.owningThread = Thread.currentThread();
        this.playerId = Objects.requireNonNull(playerId);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.threshold = threshold;
        this.waterTiming = waterTiming;
        this.turnRules = turnRules;
        var evaluated = CooksoniaOutcome.evaluate(Objects.requireNonNull(initial), threshold);
        this.state = evaluated.state();
        this.outcome = evaluated.status();
    }

    /** 仅服务器内部结算使用，不能直接序列化给客户端；对外显示使用 view。 */
    public Optional<CooksoniaGameState> snapshot() { checkThread(); return Optional.ofNullable(state); }
    /** 宿主取得服务器分配的身份以发送首帧；读取身份不授予其他玩家查看权限。 */
    public UUID sessionId() { checkThread(); return sessionId; }
    void toolAccess(java.util.function.Predicate<CooksoniaToolRules.Tool> access) {
        checkThread();
        toolAccess = Objects.requireNonNull(access);
    }

    public java.util.Set<CooksoniaToolRules.Tool> enabledTools() {
        checkThread();
        var enabled = new java.util.HashSet<CooksoniaToolRules.Tool>();
        if (state != null) {
            for (var tool : CooksoniaToolRules.Tool.values()) if (toolAccess.test(tool)) enabled.add(tool);
        }
        return java.util.Set.copyOf(enabled);
    }
    public Optional<CooksoniaOutcome.Status> outcome() { checkThread(); return Optional.ofNullable(outcome); }
    public int cooldownRemaining() { checkThread(); return cooldown; }
    public long revision() { checkThread(); return revision; }

    /** 只向本对局玩家提供可见信息；冷却变化不重建棋盘／单位内容。 */
    public Optional<CooksoniaView> view(UUID viewer, UUID requestedSession) {
        checkThread();
        if (state == null || !playerId.equals(viewer) || !sessionId.equals(requestedSession)) return Optional.empty();
        if (cachedContent == null) cachedContent = CooksoniaView.project(state);
        return Optional.of(new CooksoniaView(sessionId, revision, cachedContent, outcome, cooldown, Optional.ofNullable(waterPlayback), enabledTools()));
    }

    /**
     * 宿主每个服务器 tick 调用；编号来自服务器计数，不能使用世界时间或客户端时钟。
     * 同编号重复回调不加速，编号跳跃也只计实际收到的一次 tick，不补算离线时间。
     * 此处只解锁操作，不成长配子体、不推进棋盘回合。
     */
    public void onServerTick(long serverTick) {
        checkThread();
        if (serverTick < 0) throw new IllegalArgumentException("Negative server tick sequence");
        if (state == null || serverTick <= lastServerTick) return;
        lastServerTick = serverTick;
        if (cooldown > 0) cooldown--;
        if (waterPlayback != null) {
            waterPlayback = waterPlayback.advance().orElse(null);
            if (waterPlayback == null) cachedContent = null;
        }
    }

    /**
     * 未来网络宿主入口：actingPlayer 来自真实连接，不能从请求体读取。
     * 客户端版本只用于比较，不能指定服务器的新版本或计算结果。
     */
    public ActionResult usePickaxe(UUID actingPlayer, CooksoniaPickaxeRequest request) {
        checkThread();
        Objects.requireNonNull(request);
        var rejected = gate(actingPlayer, request.sessionId(), state);
        if (rejected.isPresent()) return new ActionResult(rejected.get(), Optional.empty(), false, revision);
        if (request.revision() != revision)
            return new ActionResult(ActionStatus.STALE_STATE, Optional.empty(), false, revision);
        return usePickaxe(actingPlayer, request.sessionId(), state, request.headId(), request.target());
    }

    /** 仅服务器内部使用；expected 必须是宿主读取的原快照，不从网络反序列化。 */
    public ActionResult usePickaxe(UUID actingPlayer, UUID actingSession, CooksoniaGameState expected,
            UUID headId, CooksoniaBoard.Pos target) {
        checkThread();
        var rejected = gate(actingPlayer, actingSession, expected);
        if (rejected.isPresent()) return new ActionResult(rejected.get(), Optional.empty(), false, revision);
        if (!toolAccess.test(CooksoniaToolRules.Tool.PICKAXE))
            return new ActionResult(ActionStatus.TOOL_LOCKED, Optional.empty(), false, revision);
        var proposed = CooksoniaPickaxe.apply(state, headId, target);
        if (!proposed.applied())
            return new ActionResult(ActionStatus.INVALID_ACTION, Optional.of(proposed.status()), false, revision);
        if (!proposed.flows().isEmpty() && waterTiming.isEmpty())
            return new ActionResult(ActionStatus.WATER_TIMING_REQUIRED, Optional.empty(), false, revision);
        var playback = proposed.flows().isEmpty() ? null
                : CooksoniaWaterPlayback.start(proposed.flows(), waterTiming.orElseThrow());
        // 先完整计算，再一次发布；异常或非法操作不会留下部分扣费，也不会启动冷却。
        var evaluated = CooksoniaOutcome.evaluate(proposed.state(), threshold);
        long nextRevision = Math.addExact(revision, 1L);
        CooksoniaView.Content playbackContent = null;
        if (playback != null) {
            var finalState = evaluated.state();
            var displayState = new CooksoniaGameState(proposed.flows().getFirst().sourceBoard(), finalState.occupancy(),
                    finalState.plants(), finalState.heads(), finalState.gametophytes(), finalState.spores(),
                    finalState.waits(), finalState.pickaxes(), finalState.score(), finalState.turn(), finalState.cleared(), finalState.producedSpores());
            playbackContent = CooksoniaView.project(displayState);
        }
        publish(evaluated, nextRevision, playbackContent, playback);
        return new ActionResult(ActionStatus.APPLIED, Optional.of(proposed.status()), evaluated.newlyCleared(), revision);
    }

    /** 服务器从连接取得 actingPlayer；先检查目标，合法后才分配新单位身份。 */
    public SporeActionResult sowSpore(UUID actingPlayer, CooksoniaSporeRequest request) {
        checkThread();
        Objects.requireNonNull(request);
        var rejected = gate(actingPlayer, request.sessionId(), state);
        if (rejected.isPresent()) return new SporeActionResult(rejected.get(), Optional.empty(), false, revision);
        if (request.revision() != revision)
            return new SporeActionResult(ActionStatus.STALE_STATE, Optional.empty(), false, revision);
        if (!toolAccess.test(CooksoniaToolRules.Tool.SPORE))
            return new SporeActionResult(ActionStatus.TOOL_LOCKED, Optional.empty(), false, revision);
        var targetStatus = CooksoniaSporePreflight.inspect(state, request.headId(), request.target());
        if (targetStatus != CooksoniaSporePreflight.Status.ALLOWED)
            return new SporeActionResult(ActionStatus.INVALID_ACTION, Optional.of(targetStatus), false, revision);
        var proposed = CooksoniaSporeSowing.sow(state, request.headId(), request.target(), UUID.randomUUID(), UUID.randomUUID());
        if (!proposed.sown())
            return new SporeActionResult(ActionStatus.INVALID_ACTION, proposed.rejection(), false, revision);
        var evaluated = CooksoniaOutcome.evaluate(proposed.state(), threshold);
        long nextRevision = Math.addExact(revision, 1L);
        publish(evaluated, nextRevision, null, null);
        return new SporeActionResult(ActionStatus.APPLIED, Optional.empty(), evaluated.newlyCleared(), revision);
    }

    /** 无活头时也允许等待；先出生／枯死和产孢子，最后判定是否全灭。 */
    public WaitActionResult waitTurn(UUID actingPlayer, CooksoniaWaitRequest request) {
        checkThread();
        Objects.requireNonNull(request);
        var rejected = gate(actingPlayer, request.sessionId(), state);
        if (rejected.isPresent()) return new WaitActionResult(rejected.get(), Optional.empty(), false, revision);
        if (request.revision() != revision)
            return new WaitActionResult(ActionStatus.STALE_STATE, Optional.empty(), false, revision);
        if (!toolAccess.test(CooksoniaToolRules.Tool.WAIT))
            return new WaitActionResult(ActionStatus.TOOL_LOCKED, Optional.empty(), false, revision);
        if (state.waits() == 0)
            return new WaitActionResult(ActionStatus.INVALID_ACTION, Optional.of(CooksoniaWait.Status.NO_WAITS), false, revision);
        if (turnRules.isEmpty())
            return new WaitActionResult(ActionStatus.TURN_RULES_REQUIRED, Optional.empty(), false, revision);
        var next = CooksoniaTurnResolver.waitTurn(state, turnRules.get()).orElseThrow();
        var evaluated = CooksoniaOutcome.evaluate(next, threshold);
        long nextRevision = Math.addExact(revision, 1L);
        publish(evaluated, nextRevision, null, null);
        return new WaitActionResult(ActionStatus.APPLIED, Optional.empty(), evaluated.newlyCleared(), revision);
    }

    /** 普通地面移动：落点及失活、原有结构推进、胜负判定全部成功后才发布。 */
    public MoveActionResult move(UUID actingPlayer, CooksoniaMoveRequest request) {
        checkThread();
        Objects.requireNonNull(request);
        var rejected = gate(actingPlayer, request.sessionId(), state);
        if (rejected.isPresent()) return new MoveActionResult(rejected.get(), Optional.empty(), false, revision);
        if (request.revision() != revision)
            return new MoveActionResult(ActionStatus.STALE_STATE, Optional.empty(), false, revision);
        var check = CooksoniaMovePreflight.inspect(state, request.headId(), request.direction());
        if (check.status() != CooksoniaMovePreflight.Status.GROUND_MOVE)
            return new MoveActionResult(ActionStatus.INVALID_ACTION, Optional.of(check.status()), false, revision);
        if (turnRules.isEmpty())
            return new MoveActionResult(ActionStatus.TURN_RULES_REQUIRED, Optional.empty(), false, revision);
        var landing = CooksoniaMoveLanding.prepare(state, request.headId(), request.direction(), UUID.randomUUID());
        if (!landing.prepared())
            return new MoveActionResult(ActionStatus.INVALID_ACTION, landing.rejection(), false, revision);
        var next = CooksoniaTurnResolver.finish(state, landing.state(), turnRules.get()).orElseThrow();
        var evaluated = CooksoniaOutcome.evaluate(next, threshold);
        long nextRevision = Math.addExact(revision, 1L);
        publish(evaluated, nextRevision, null, null);
        return new MoveActionResult(ActionStatus.APPLIED, Optional.empty(), evaluated.newlyCleared(), revision);
    }

    /** 分叉无回合推进，不要求萌发或成熟规则；仍使用同一身份、版本与节奏门控。 */
    public BranchActionResult branch(UUID actingPlayer, CooksoniaBranchRequest request) {
        checkThread();
        Objects.requireNonNull(request);
        var rejected = gate(actingPlayer, request.sessionId(), state);
        if (rejected.isPresent()) return new BranchActionResult(rejected.get(), Optional.empty(), false, revision);
        if (request.revision() != revision)
            return new BranchActionResult(ActionStatus.STALE_STATE, Optional.empty(), false, revision);
        var check = CooksoniaBranchPreflight.inspect(state, request.stemId(), request.direction());
        if (check != CooksoniaBranchPreflight.Status.ALLOWED)
            return new BranchActionResult(ActionStatus.INVALID_ACTION, Optional.of(check), false, revision);
        var proposed = CooksoniaBranch.apply(state, request.stemId(), request.direction(), UUID.randomUUID());
        if (!proposed.branched())
            return new BranchActionResult(ActionStatus.INVALID_ACTION, proposed.rejection(), false, revision);
        var evaluated = CooksoniaOutcome.evaluate(proposed.state(), threshold);
        long nextRevision = Math.addExact(revision, 1L);
        publish(evaluated, nextRevision, null, null);
        return new BranchActionResult(ActionStatus.APPLIED, Optional.empty(), evaluated.newlyCleared(), revision);
    }

    /** 调用前完成所有可能失败的计算；各操作共用提交，不产生逐层中间状态。 */
    private void publish(CooksoniaOutcome.Result evaluated, long nextRevision,
            CooksoniaView.Content presentation, CooksoniaWaterPlayback playback) {
        state = evaluated.state();
        outcome = evaluated.status();
        revision = nextRevision;
        cachedContent = presentation;
        waterPlayback = playback;
        cooldown = ACTION_LOCK_TICKS;
    }

    /** 玩家退出界面、断线或死亡的服务端事件调用；关闭无需等待冷却，不能继续读回棋盘。 */
    public void close() {
        checkThread();
        state = null;
        outcome = null;
        cachedContent = null;
        waterPlayback = null;
        cooldown = 0;
        toolAccess = tool -> false;
    }

    private Optional<ActionStatus> gate(UUID actingPlayer, UUID actingSession, CooksoniaGameState expected) {
        if (!playerId.equals(actingPlayer)) return Optional.of(ActionStatus.WRONG_PLAYER);
        if (!sessionId.equals(actingSession)) return Optional.of(ActionStatus.WRONG_SESSION);
        if (state == null) return Optional.of(ActionStatus.CLOSED);
        if (outcome.ended()) return Optional.of(ActionStatus.ENDED);
        if (expected != state) return Optional.of(ActionStatus.STALE_STATE);
        if (waterPlayback != null) return Optional.of(ActionStatus.FLOWING);
        if (cooldown > 0) return Optional.of(ActionStatus.COOLDOWN);
        return Optional.empty();
    }

    private void checkThread() {
        if (Thread.currentThread() != owningThread)
            throw new IllegalStateException("Cooksonia session accessed outside its owning server thread");
    }
}
