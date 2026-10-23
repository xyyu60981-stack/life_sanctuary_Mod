package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** C 阶段中央纤维根落地后建立地下系统；不替代 C 阶段的土壤/硬度预检或结构提交。 */
public final class RhizomeInitialization {
    private RhizomeInitialization() {}
    // 缺失/零值也不能被解释为“再领一次”；使用 containsKey 判定已经初始化。
    public static final String INITIALIZED = "rhizome_system_initialized";
    public enum Reason {
        CREATED, ALREADY_EXISTS, READ_ONLY, BUSY, STALE_PLANT, SOURCE_DISABLED,
        DESCENDANT, INITIALIZATION_USED, WRONG_STAGE, UNAVAILABLE, WRONG_ROOT,
        CONFLICT, RECOVERY_REQUIRED, SHARED_SYSTEM, BRANCH_CREATED
    }
    public record Result(Reason reason, Optional<PlantRhizomeSystem> system) {}

    /**
     * establishmentStage/fiberRole 由物种稳定元数据提供，origin 是已转换并归属该株的最低中央纤维块。
     * 全部条件通过后才抽两端 C 长度和共享名额；系统 ID 沿用建立者 UUID，重复调用不重新抽数。
     */
    public static Result create(ServerLevel level, PlantIndividualState expected, String establishmentStage,
            BlockPos origin, String fiberRole, Predicate<BlockState> validRoot, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        Objects.requireNonNull(validRoot);
        Objects.requireNonNull(random);
        if (fiberRole == null || fiberRole.isBlank()) throw new IllegalArgumentException("Missing root fiber role");
        var individuals = PlantIndividuals.get(level);
        var systems = PlantRhizomeSystems.get(level);
        if (individuals.isReadOnly() || systems.isReadOnly()) return rejected(Reason.READ_ONLY);
        if (individuals.isApplyingStructure() || systems.isApplyingStructure()) return rejected(Reason.BUSY);
        if (!individuals.find(expected.id()).filter(expected::equals).isPresent()) return rejected(Reason.STALE_PLANT);
        if (expected.source() != PlantSource.PLAYER) return rejected(Reason.SOURCE_DISABLED);
        var shared = systems.forShoot(expected.id()).orElse(null);
        if (shared != null) {
            if (!shared.species().equals(expected.species()) || !shared.population().equals(expected.population())
                    || !shared.containsRegion(expected.base())) return rejected(Reason.CONFLICT);
            if (systems.needsRecovery(shared.id())) return new Result(Reason.RECOVERY_REQUIRED, Optional.of(shared));
        }
        if (shared == null && expected.origin() == PlantOrigin.AUTOMATIC_SPREAD) return rejected(Reason.DESCENDANT);
        if (shared == null && expected.origin() != PlantOrigin.PLAYER_CULTIVATED) return rejected(Reason.SOURCE_DISABLED);
        var existing = shared == null ? systems.find(expected.id()).orElse(null) : shared.growth()
                .filter(growth -> growth.branches().containsKey(expected.id())).map(ignored -> shared).orElse(null);
        if (existing != null) {
            BlockPos existingRoot = shared == null ? existing.origin() : existing.growth().orElseThrow().branches().get(expected.id()).root();
            if (!existingRoot.equals(origin) || !existing.species().equals(expected.species())
                    || !existing.population().equals(expected.population()) || existing.source() != expected.source())
                return rejected(Reason.CONFLICT);
            if (!expected.reproduction().containsKey(INITIALIZED) && !systems.needsRecovery(existing.id())) {
                try {
                    if (!markInitialized(individuals, expected)) systems.requireRecovery(existing.id());
                } catch (RuntimeException failure) {
                    systems.requireRecovery(existing.id());
                    org.slf4j.LoggerFactory.getLogger(RhizomeInitialization.class).error("Cannot restore rhizome initialization marker", failure);
                }
            }
            return new Result(systems.needsRecovery(existing.id()) ? Reason.RECOVERY_REQUIRED
                    : shared == null ? Reason.ALREADY_EXISTS : Reason.SHARED_SYSTEM,
                    Optional.of(existing));
        }
        if (expected.reproduction().containsKey(INITIALIZED)) return rejected(Reason.INITIALIZATION_USED);
        var definition = PlantSpeciesRegistry.find(expected.species()).orElse(null);
        if (definition == null || !definition.stages().containsKey(establishmentStage)
                || RhizomePopulation.findStages(expected.species()).isEmpty()
                || !definition.population().map(population -> population.horizontalSize() == 32).orElse(false)
                || !expected.stage().equals(establishmentStage) || expected.pruned()
                || !expected.environmentReasons().isEmpty()) return rejected(Reason.WRONG_STAGE);
        if (origin.getX() != expected.base().getX() || origin.getZ() != expected.base().getZ()
                || origin.getY() > expected.base().getY()
                || !individuals.partRole(expected.id(), origin).filter(fiberRole::equals).isPresent())
            return rejected(Reason.WRONG_ROOT);
        for (var pos : individuals.partsOf(expected.id()).keySet())
            if (Math.floorDiv(pos.getX(), 32) != Math.floorDiv(origin.getX(), 32)
                    || Math.floorDiv(pos.getZ(), 32) != Math.floorDiv(origin.getZ(), 32)) return rejected(Reason.CONFLICT);
        int x = origin.getX() >> 4, z = origin.getZ() >> 4;
        if (level.isOutsideBuildHeight(origin) || level.isOutsideBuildHeight(expected.base())
                || !level.getWorldBorder().isWithinBounds(origin)
                || !level.getChunkSource().hasChunk(x, z)
                || !level.getChunkSource().isPositionTicking(ChunkPos.asLong(x, z))) return rejected(Reason.UNAVAILABLE);
        for (var owner : java.util.List.of(systems.atOrigin(origin), systems.atRhizome(origin),
                systems.atAttachment(origin), systems.atShootBase(origin)))
            if (owner.isPresent() && (shared == null || !owner.get().id().equals(shared.id()))) return rejected(Reason.CONFLICT);
        var rootState = level.getBlockState(origin);
        if (rootState.hasBlockEntity() || !validRoot.test(rootState)) return rejected(Reason.WRONG_ROOT);
        if (shared != null) {
            for (int y = origin.getY(); y < expected.base().getY(); y++) {
                BlockPos pos = new BlockPos(origin.getX(), y, origin.getZ());
                if ((shared.rhizomes().contains(pos) || shared.risingRhizomes().contains(pos))
                        && (!individuals.partRole(expected.id(), pos).filter(fiberRole::equals).isPresent()
                            || !validRoot.test(level.getBlockState(pos)))) return rejected(Reason.WRONG_ROOT);
            }
        }
        // validRoot 必须只读；最后一次复核发生在任何随机抽取及元数据写入之前。
        if (!individuals.find(expected.id()).filter(expected::equals).isPresent()
                || !individuals.partRole(expected.id(), origin).filter(fiberRole::equals).isPresent()
                || !level.getBlockState(origin).equals(rootState)) return rejected(Reason.STALE_PLANT);
        PlantRhizomeSystem system;
        if (shared != null) {
            system = shared.establishBranch(expected.id(), origin, random).orElse(null);
            if (system == null || !systems.replace(shared, system)) return rejected(Reason.CONFLICT);
        } else {
            var growth = RhizomeGrowthState.initialC(origin, random);
            system = new PlantRhizomeSystem(expected.id(), expected.species(), expected.population(), origin,
                    PlantSource.PLAYER, 2 + random.nextInt(2), Set.of(), Map.of(), Optional.of(growth));
            if (!systems.createInitialRoot(system)) return rejected(Reason.CONFLICT);
        }
        try {
            if (markInitialized(individuals, expected))
                return new Result(shared == null ? Reason.CREATED : Reason.BRANCH_CREATED, Optional.of(system));
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(RhizomeInitialization.class).error("Cannot mark initialized rhizome owner", failure);
        }
        systems.requireRecovery(system.id());
        return new Result(Reason.RECOVERY_REQUIRED, Optional.of(system));
    }

    private static boolean markInitialized(PlantIndividuals store, PlantIndividualState expected) {
        var resources = new HashMap<>(expected.reproduction());
        resources.put(INITIALIZED, 1);
        return store.replace(expected, expected.withReproduction(resources));
    }

    private static Result rejected(Reason reason) { return new Result(reason, Optional.empty()); }
}
