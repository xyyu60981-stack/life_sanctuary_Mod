package com.example.life_sanctuary;

/** 由物种掉落适配器在成功提交结构事务后调用；不按叶片部件格数重复发草杆。 */
public final class PlantLeafDropRules {
    private PlantLeafDropRules() {}
    public enum Cause { DIRECT_LEAF_CUT, DIRECT_SKIRT_CUT, BUD_CASCADE, STEM_CASCADE, AUTOMATIC_SHEDDING, ENVIRONMENTAL_DEATH }

    public static boolean allowsGrassStalks(Cause cause, boolean indirectDrops) {
        return switch (java.util.Objects.requireNonNull(cause)) {
            case DIRECT_LEAF_CUT, DIRECT_SKIRT_CUT -> true;
            case BUD_CASCADE, STEM_CASCADE -> indirectDrops;
            case AUTOMATIC_SHEDDING, ENVIRONMENTAL_DEATH -> false;
        };
    }

    public static boolean allowsGrassStalks(Cause cause) {
        return allowsGrassStalks(cause, Config.INDIRECT_CROWN_LEAF_DROPS.get());
    }
}
