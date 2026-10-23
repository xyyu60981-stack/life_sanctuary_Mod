package com.example.life_sanctuary;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 出苗提案的即时复核；READY 不是预留名额，不能跨 tick 当作提交凭证。 */
public final class RhizomeShootPreflight {
    private RhizomeShootPreflight() {}

    public enum Reason { READY, PATH_REJECTED, WORLD_CHANGED, INVALID_CHILD, CHILD_EXISTS, SHOOT_NOT_READY }

    public record Result(Reason reason, Optional<RhizomeShootPath.Result> pathCheck,
            Optional<PlantRhizomeSystem> proposedSystem) {
        public boolean ready() { return reason == Reason.READY; }
    }

    /**
     * child 是尚未登记的幼丛快照。先复核路径，再提出系统占额结果，不创建子体或保存系统。
     * 读取范围仅限原竖向路径及最多四格地上空间，不扫描种群或成年体空间。
     */
    public static Result check(ServerLevel level, RhizomeShootStructure.Proposal proposal,
            PlantIndividualState child, Predicate<BlockState> validRoot) {
        Objects.requireNonNull(proposal);
        Objects.requireNonNull(child);
        var path = proposal.path();
        var systems = PlantRhizomeSystems.get(level);
        if (!systems.shootTimer(path.system().id()).map(RhizomeShootTimer::ready).orElse(false))
            return rejected(Reason.SHOOT_NOT_READY);
        var checked = RhizomeShootPath.inspect(level, systems, path.system(), path.root(), path.surface(), validRoot);
        if (!checked.ready()) return new Result(Reason.PATH_REJECTED, Optional.of(checked), Optional.empty());
        // 比较完整读集及出苗类型，头部未写入的空气也不能省略。
        if (!path.equals(checked.path().orElseThrow()))
            return new Result(Reason.WORLD_CHANGED, Optional.of(checked), Optional.empty());
        if (!child.base().equals(path.shootBase()) || child.id().equals(path.system().id())
                || child.source() != PlantSource.PLAYER)
            return rejected(Reason.INVALID_CHILD);
        var individuals = PlantIndividuals.get(level);
        if (individuals.find(child.id()).isPresent() || systems.forShoot(child.id()).isPresent())
            return rejected(Reason.CHILD_EXISTS);
        var next = path.system().claimShootWithPath(child, path);
        if (next.isEmpty()) return rejected(Reason.INVALID_CHILD);
        return new Result(Reason.READY, Optional.of(checked), next);
    }

    private static Result rejected(Reason reason) {
        return new Result(reason, Optional.empty(), Optional.empty());
    }
}
