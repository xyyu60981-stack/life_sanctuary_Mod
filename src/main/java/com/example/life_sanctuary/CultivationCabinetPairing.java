package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 柜体配对的几何规则；只使用调用方提供的方块快照，不读取世界或加载相邻区块。 */
public final class CultivationCabinetPairing {
    private CultivationCabinetPairing() {}
    public enum Part implements net.minecraft.util.StringRepresentable {
        SINGLE, FIRST, SECOND;
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
        public Part opposite() {
            return switch (this) { case SINGLE -> SINGLE; case FIRST -> SECOND; case SECOND -> FIRST; };
        }
    }

    public record Half(BlockPos pos, Direction facing, Part part) {
        public Half {
            pos = java.util.Objects.requireNonNull(pos).immutable();
            java.util.Objects.requireNonNull(facing);
            java.util.Objects.requireNonNull(part);
            if (facing == Direction.UP || facing == Direction.DOWN)
                throw new IllegalArgumentException("Cultivation cabinet must face horizontally");
        }

        /** 配对预期位置仅是坐标，调用方须确认该位置已加载且有互相匹配的柜体。 */
        public Optional<BlockPos> partnerPos() {
            return part == Part.SINGLE ? Optional.empty() : Optional.of(pos.relative(
                    part == Part.FIRST ? facing.getClockWise() : facing.getCounterClockWise()));
        }

        public Half asSingle() { return new Half(pos, facing, Part.SINGLE); }
    }

    /** FIRST 到 SECOND 恒为朝向的顺时针侧；固定槽序与传参、加载顺序无关。 */
    public record Pair(Half first, Half second) {
        public Pair {
            java.util.Objects.requireNonNull(first);
            java.util.Objects.requireNonNull(second);
            if (first.part() != Part.FIRST || second.part() != Part.SECOND || first.facing() != second.facing()
                    || !first.partnerPos().orElseThrow().equals(second.pos())
                    || !second.partnerPos().orElseThrow().equals(first.pos()))
                throw new IllegalArgumentException("Inconsistent cultivation cabinet pair");
        }
    }

    /** 仅接受同朝向、同高度、左右相邻的两个单柜；已连接柜不能再次参与连接。 */
    public static Optional<Pair> join(Half a, Half b) {
        java.util.Objects.requireNonNull(a);
        java.util.Objects.requireNonNull(b);
        if (a.part() != Part.SINGLE || b.part() != Part.SINGLE || a.facing() != b.facing()) return Optional.empty();
        if (a.pos().relative(a.facing().getClockWise()).equals(b.pos()))
            return Optional.of(new Pair(new Half(a.pos(), a.facing(), Part.FIRST), new Half(b.pos(), b.facing(), Part.SECOND)));
        if (b.pos().relative(b.facing().getClockWise()).equals(a.pos()))
            return Optional.of(new Pair(new Half(b.pos(), b.facing(), Part.FIRST), new Half(a.pos(), a.facing(), Part.SECOND)));
        return Optional.empty();
    }

    /** 已连接柜只接受双向一致的配对，避免凭单侧状态认领邻柜。 */
    public static Optional<Pair> existing(Half a, Half b) {
        java.util.Objects.requireNonNull(a);
        java.util.Objects.requireNonNull(b);
        Half first = a.part() == Part.FIRST ? a : b;
        Half second = a.part() == Part.FIRST ? b : a;
        if (first.part() != Part.FIRST || second.part() != Part.SECOND || first.facing() != second.facing()
                || !first.partnerPos().orElseThrow().equals(second.pos())
                || !second.partnerPos().orElseThrow().equals(first.pos())) return Optional.empty();
        return Optional.of(new Pair(first, second));
    }
}
