package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * 单片独立叶的资源快照。anchor 是物种提供的交互/归属锚点，不代表整片叶只占一格。
 * 资源身份由 PlantLeafSet 的稳定键提供，外形、方向和是否存在均不能刷新额度。
 */
public record PlantLeafState(BlockPos anchor, String role, boolean present, boolean fertile,
        int remainingCollections) {
    public PlantLeafState {
        anchor = anchor.immutable();
        if (role == null || role.isBlank() || remainingCollections < 0 || !fertile && remainingCollections != 0)
            throw new IllegalArgumentException("Invalid independent leaf state");
    }

    /** 砍掉但未采空的可育叶仍占名额；已采空的叶即使尚未脱落也不占。 */
    public boolean reservesFertileSlot() { return fertile && remainingCollections > 0; }
    public boolean canCollect() { return present && reservesFertileSlot(); }
    public boolean exhausted() { return fertile && remainingCollections == 0; }

    /** 只是构建扣次快照，不负责袋子交易、世界改块或持久化。 */
    public Optional<PlantLeafState> collect() {
        return canCollect() ? Optional.of(new PlantLeafState(anchor, role, true, true, remainingCollections - 1))
                : Optional.empty();
    }

    /** 普通切除与自动脱落共用“外形消失”，掉落物和脱落资格由调用方分别处理。 */
    public PlantLeafState withoutStructure() {
        return present ? new PlantLeafState(anchor, role, false, fertile, remainingCollections) : this;
    }

    /** 相同资源单元的更新只可减次或移除外形，不能通过原键补叶/改位置/改可育性。 */
    boolean permits(PlantLeafState next) {
        return anchor.equals(next.anchor) && role.equals(next.role) && fertile == next.fertile
                && (present || !next.present) && next.remainingCollections <= remainingCollections
                && (next.remainingCollections == remainingCollections || present && next.present
                    && next.remainingCollections == remainingCollections - 1);
    }
}
