package com.example.life_sanctuary;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 幼丛个体注销后的系统关联清理；切断地下茎和临时暂停不能释放名额。 */
public final class RhizomeShootLifecycle {
    private RhizomeShootLifecycle() {}
    public enum Result { RELEASED, STILL_ALIVE, READ_ONLY, STALE_SYSTEM, RECOVERY_REQUIRED }

    /** 整株移除/枯死转换写世界前检查；归属损坏时不能先删个体再试图清理系统。 */
    public static boolean canRetire(ServerLevel level, PlantIndividualState child) {
        var systems = PlantRhizomeSystems.get(level);
        var individuals = PlantIndividuals.get(level);
        if (systems.isReadOnly() || individuals.isReadOnly() || systems.isApplyingStructure()
                || individuals.isApplyingStructure() || !individuals.find(child.id()).filter(child::equals).isPresent()) return false;
        var system = systems.forShoot(child.id()).orElse(null);
        if (system == null) return true;
        var shoot = system.shoots().get(child.id());
        return !systems.needsRecovery(system.id()) && shoot != null && shoot.base().equals(child.base())
                && system.species().equals(child.species()) && system.population().equals(child.population());
    }

    /**
     * 必须先完成个体注销；只修改关联，不删除地下茎/细茎/死体，也不移动系统原点。
     * 无外部回调的元数据操作，同一服务器调用内完成；失败不主动遗忘关联。
     */
    public static Result releaseRemoved(ServerLevel level, PlantRhizomeSystem expected, UUID childId) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var systems = PlantRhizomeSystems.get(level);
        var individuals = PlantIndividuals.get(level);
        if (systems.isReadOnly() || individuals.isReadOnly()) return Result.READ_ONLY;
        if (systems.needsRecovery(expected.id()) || systems.isApplyingStructure() || individuals.isApplyingStructure())
            return Result.RECOVERY_REQUIRED;
        if (!systems.find(expected.id()).filter(expected::equals).isPresent()
                || !expected.shoots().containsKey(childId)
                || !systems.forShoot(childId).filter(expected::equals).isPresent()) return Result.STALE_SYSTEM;
        if (individuals.find(childId).isPresent() || !individuals.partsOf(childId).isEmpty()) return Result.STILL_ALIVE;
        return systems.replace(expected, expected.releaseShoot(childId)) ? Result.RELEASED : Result.STALE_SYSTEM;
    }
}
