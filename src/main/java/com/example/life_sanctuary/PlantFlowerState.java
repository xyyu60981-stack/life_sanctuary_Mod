package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;

/** 性别保存在实际生殖结构上，不把成年主干整体标为雌或雄。 */
public record PlantFlowerState(BlockPos position, String role, Sex sex, Phase phase, int seeds) {
    public enum Sex { MALE, FEMALE, BISEXUAL }
    public enum Phase { CLOSED, FLOWERING, FERTILIZED, SEEDS_READY }

    public PlantFlowerState {
        position = position.immutable();
        java.util.Objects.requireNonNull(sex);
        java.util.Objects.requireNonNull(phase);
        if (role == null || role.isBlank() || seeds < 0)
            throw new IllegalArgumentException("Invalid flower state");
        boolean seeded = phase == Phase.FERTILIZED || phase == Phase.SEEDS_READY;
        if (seeded != (seeds > 0) || (seeded && sex == Sex.MALE))
            throw new IllegalArgumentException("Seed state does not match flower sex/phase");
    }

    public boolean providesPollen() { return phase == Phase.FLOWERING && sex != Sex.FEMALE; }
    public boolean receivesPollen() { return phase == Phase.FLOWERING && sex != Sex.MALE; }

    public PlantFlowerState withPhase(Phase phase, int seeds) {
        return new PlantFlowerState(position, role, sex, phase, seeds);
    }

    /** 剪止清空未成熟受精信息，已成熟种子仍可采，不重新补种。 */
    public PlantFlowerState pruned() {
        return phase == Phase.SEEDS_READY ? this : withPhase(Phase.CLOSED, 0);
    }

    boolean permits(PlantFlowerState next) {
        if (!position.equals(next.position) || !role.equals(next.role) || sex != next.sex) return false;
        if (equals(next)) return true;
        return switch (phase) {
            case CLOSED -> next.phase == Phase.FLOWERING;
            case FLOWERING -> next.phase == Phase.CLOSED || next.phase == Phase.FERTILIZED || next.phase == Phase.SEEDS_READY;
            case FERTILIZED -> next.phase == Phase.SEEDS_READY && seeds == next.seeds;
            case SEEDS_READY -> next.phase == Phase.CLOSED;
        };
    }
}
