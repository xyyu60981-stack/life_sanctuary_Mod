package com.example.life_sanctuary;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.util.RandomSource;

/** 独立叶可育概率工具；不注册物种、不分配资源、不改变成年锁定上限。 */
public final class PlantLeafFertility {
    private PlantLeafFertility() {}
    public static final Chance QUARTER = new Chance(1, 4);
    public static final Chance TWO_FIFTHS = new Chance(2, 5);
    public static final Chance THREE_EIGHTHS = new Chance(3, 8);

    /** 用整数比例精确表示 25%、40%、37.5%，不做浮点近似或百分比取整。 */
    public record Chance(int successfulOutcomes, int totalOutcomes) {
        public Chance {
            if (totalOutcomes <= 0 || successfulOutcomes < 0 || successfulOutcomes > totalOutcomes)
                throw new IllegalArgumentException("Invalid leaf fertility chance");
        }
        public boolean roll(RandomSource random) {
            Objects.requireNonNull(random);
            if (successfulOutcomes == 0) return false;
            if (successfulOutcomes == totalOutcomes) return true;
            return random.nextInt(totalOutcomes) < successfulOutcomes;
        }
    }

    /**
     * 仅判定是否可新发一份可育资源。被砍叶保留额度已计入 occupied，不能当作空名额。
     * 资源继承不走此入口；物种应先做空间预检，再判概率并构造对应外形提案。
     */
    public static boolean rollFresh(PlantLeafSet leaves, Chance chance, RandomSource random) {
        Objects.requireNonNull(leaves);
        Objects.requireNonNull(chance);
        Objects.requireNonNull(random);
        if (leaves.freeLeafSlots() == 0 || leaves.freeFertileSlots() == 0 || retainedKey(leaves).isPresent()) return false;
        return chance.roll(random);
    }

    public enum Kind { NO_LEAF_SLOT, STERILE, FRESH_FERTILE, INHERITED_FERTILE }
    public record Decision(Kind kind, Optional<String> retainedKey, int collections) {
        public Decision {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(retainedKey);
            boolean fertile = kind == Kind.FRESH_FERTILE || kind == Kind.INHERITED_FERTILE;
            if (fertile ? collections <= 0 : collections != 0)
                throw new IllegalArgumentException("Invalid leaf fertility allocation");
            if (retainedKey.isPresent() != (kind == Kind.INHERITED_FERTILE)
                    || retainedKey.filter(String::isBlank).isPresent())
                throw new IllegalArgumentException("Invalid retained leaf allocation");
        }
    }

    /** 保留额度按稳定键顺序选择，不重新抽选择对象，也不依赖 Map 的遍历顺序。 */
    public static Optional<String> retainedKey(PlantLeafSet leaves) {
        return leaves.leaves().entrySet().stream().filter(entry -> !entry.getValue().present()
                && entry.getValue().reservesFertileSlot()).map(java.util.Map.Entry::getKey).min(String::compareTo);
    }

    /** 仍判可育；判中时优先继承，判为不育不动保留额度；没有旧额度才可新发。 */
    public static Decision decideRegrowth(PlantLeafSet leaves, Chance chance, int freshCollections, RandomSource random) {
        Objects.requireNonNull(leaves);
        Objects.requireNonNull(chance);
        Objects.requireNonNull(random);
        if (freshCollections <= 0) throw new IllegalArgumentException("Fresh fertile leaf needs an explicit positive quota");
        if (leaves.freeLeafSlots() == 0) return new Decision(Kind.NO_LEAF_SLOT, Optional.empty(), 0);
        var retained = retainedKey(leaves);
        if ((retained.isEmpty() && leaves.freeFertileSlots() == 0) || !chance.roll(random))
            return new Decision(Kind.STERILE, Optional.empty(), 0);
        if (retained.isPresent()) return new Decision(Kind.INHERITED_FERTILE, retained,
                leaves.leaves().get(retained.get()).remainingCollections());
        return new Decision(Kind.FRESH_FERTILE, Optional.empty(), freshCollections);
    }
}
