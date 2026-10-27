package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

/** 连接／拆分的纯状态提案；未来方块事务须一起锁定并提交两侧，不能据此直接复制批次。 */
public final class CultivationCabinetConnection {
    private CultivationCabinetConnection() {}

    /** 配对几何和按位置排序的完整四槽提案；不代表世界和原单柜数据已提交。 */
    public record Joined(CultivationCabinetPairing.Pair pair, CultivationSlots contents) {
        public Joined {
            java.util.Objects.requireNonNull(pair);
            java.util.Objects.requireNonNull(contents);
            if (contents.capacity() != CultivationRules.LARGE_SLOT_COUNT || contents.isReadOnly())
                throw new IllegalArgumentException("Joined cultivation cabinet requires readable four-slot contents");
        }

        /** 提交前重新核对两侧几何及完整槽数据；计时变化也会使旧提案失效。 */
        public boolean matches(CultivationCabinetPairing.Half a, CultivationSlots aData,
                CultivationCabinetPairing.Half b, CultivationSlots bData) {
            return join(a, aData, b, bData).filter(this::equals).isPresent();
        }
    }

    public record Single(CultivationCabinetPairing.Half half, CultivationSlots contents) {
        public Single {
            java.util.Objects.requireNonNull(half);
            java.util.Objects.requireNonNull(contents);
            if (half.part() != CultivationCabinetPairing.Part.SINGLE
                    || contents.capacity() != CultivationRules.SINGLE_SLOT_COUNT || contents.isReadOnly())
                throw new IllegalArgumentException("Single cultivation cabinet requires readable two-slot contents");
        }
    }

    public record Separated(Single first, Single second) {}

    /** 传参顺序可颠倒，槽序始终由配对坐标决定，避免调用方将两侧批次交换。 */
    public static Optional<Joined> join(CultivationCabinetPairing.Half a, CultivationSlots aData,
            CultivationCabinetPairing.Half b, CultivationSlots bData) {
        java.util.Objects.requireNonNull(aData);
        java.util.Objects.requireNonNull(bData);
        return CultivationCabinetPairing.join(a, b).flatMap(pair -> {
            boolean aFirst = pair.first().pos().equals(a.pos());
            return join(aFirst ? aData : bData, aFirst ? bData : aData)
                    .map(contents -> new Joined(pair, contents));
        });
    }

    /** 核对当前双向配对与完整内容后，按固定归属恢复两个单柜；不自动掉落任何物品。 */
    public static Optional<Separated> split(Joined expected, CultivationCabinetPairing.Half a,
            CultivationCabinetPairing.Half b, CultivationSlots currentContents) {
        java.util.Objects.requireNonNull(expected);
        java.util.Objects.requireNonNull(currentContents);
        var pair = CultivationCabinetPairing.existing(a, b).orElse(null);
        if (!expected.pair().equals(pair) || !expected.contents().equals(currentContents)) return Optional.empty();
        return split(currentContents).map(halves -> new Separated(
                new Single(pair.first().asSingle(), halves.first()),
                new Single(pair.second().asSingle(), halves.second())));
    }

    /** 两侧各保留原槽顺序；第一侧映射大柜 0、1，第二侧映射 2、3。 */
    public static Optional<CultivationSlots> join(CultivationSlots first, CultivationSlots second) {
        java.util.Objects.requireNonNull(first);
        java.util.Objects.requireNonNull(second);
        if (first.capacity() != CultivationRules.SINGLE_SLOT_COUNT
                || second.capacity() != CultivationRules.SINGLE_SLOT_COUNT
                || first.isReadOnly() || second.isReadOnly()) return Optional.empty();
        var identities = new HashSet<UUID>();
        var combined = new ArrayList<>(first.slots());
        combined.addAll(second.slots());
        for (var slot : combined) {
            if (slot.orElse(null) instanceof CultivationBatchCodec.Loaded loaded
                    && !identities.add(loaded.batch().id())) return Optional.empty();
        }
        return Optional.of(new CultivationSlots(combined));
    }

    public record Halves(CultivationSlots first, CultivationSlots second) {}

    /** 只分配原批次，既不重抽时长也不重建 ID；被拆掉一侧如何掉落由未来方块事务决定。 */
    public static Optional<Halves> split(CultivationSlots large) {
        java.util.Objects.requireNonNull(large);
        if (large.capacity() != CultivationRules.LARGE_SLOT_COUNT || large.isReadOnly()) return Optional.empty();
        int half = CultivationRules.SINGLE_SLOT_COUNT;
        return Optional.of(new Halves(new CultivationSlots(large.slots().subList(0, half)),
                new CultivationSlots(large.slots().subList(half, large.capacity()))));
    }
}
