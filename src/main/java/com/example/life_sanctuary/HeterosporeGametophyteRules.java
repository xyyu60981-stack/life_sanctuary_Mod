package com.example.life_sanctuary;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/** Pure pairing rules. The subsequent world adapter owns elapsed clocks, index snapshots and placement transactions. */
public final class HeterosporeGametophyteRules {
    private HeterosporeGametophyteRules() {}
    public static final int MATURITY_TICKS = 24_000, SEARCH_INTERVAL = 2_000;
    public static final int MIXED_MIN_TICKS = 72_000, MIXED_MAX_TICKS = 120_000;
    public enum Kind { FEMALE, MALE, MIXED }
    public record Candidate(UUID id, ResourceLocation species, String population, BlockPos pos, Kind kind, boolean mature, boolean usable) {
        public Candidate {
            Objects.requireNonNull(id); Objects.requireNonNull(species); Objects.requireNonNull(kind); pos = pos.immutable();
            if (population == null || population.isBlank()) throw new IllegalArgumentException("Explicit gametophyte population required");
        }
    }
    public static Kind kind(SporeBagContents.Form form) { return switch (form) {
        case MEGA -> Kind.FEMALE; case MICRO -> Kind.MALE; case MIXED -> Kind.MIXED;
        default -> throw new IllegalArgumentException("Undifferentiated spores keep their existing lifecycle");
    }; }
    public static List<Candidate> donors(Candidate receiver, List<Candidate> candidates, int attempt) {
        requireAttempt(attempt);
        if (!receiver.mature() || !receiver.usable() || receiver.kind() == Kind.MALE) return List.of();
        var accepted = new java.util.LinkedHashMap<UUID, Candidate>();
        for (var donor : List.copyOf(candidates)) {
            if (donor.id().equals(receiver.id()) || !donor.mature() || !donor.usable() || donor.kind() == Kind.FEMALE
                    || !donor.species().equals(receiver.species()) || attempt < 3 && !donor.population().equals(receiver.population())
                    || Math.abs((long) donor.pos().getX() - receiver.pos().getX()) > 2
                    || Math.abs((long) donor.pos().getY() - receiver.pos().getY()) > 2
                    || Math.abs((long) donor.pos().getZ() - receiver.pos().getZ()) > 2) continue;
            var old = accepted.putIfAbsent(donor.id(), donor);
            if (old != null && !old.equals(donor)) throw new IllegalArgumentException("Conflicting gametophyte identity");
        }
        return List.copyOf(accepted.values());
    }
    public static int successPercent(int donors, int attempt) {
        requireAttempt(attempt);
        if (donors < 0) throw new IllegalArgumentException("Negative donor count");
        return donors == 0 ? 0 : attempt == 3 ? 100 : (int) Math.min(100, 40 + 15L * (donors - 1));
    }
    public static boolean disappearAfterFailure(Kind kind, int attempt) { requireAttempt(attempt); return kind == Kind.FEMALE && attempt == 3; }
    public static int fertilizedDelay(RandomSource random) { return 12_000 + random.nextInt(12_001); }
    public static int failureDelay(RandomSource random) { return 1_000 + random.nextInt(2_001); }
    public static int mixedDelay(RandomSource random) { return MIXED_MIN_TICKS + random.nextInt(MIXED_MAX_TICKS - MIXED_MIN_TICKS + 1); }
    /** Additional lifetime after the male gametophyte has matured, not after sowing. */
    public static int maleLifetime(RandomSource random) { return 72_000 + random.nextInt(48_001); }
    private static void requireAttempt(int attempt) { if (attempt < 1 || attempt > 3) throw new IllegalArgumentException("Three pairing attempts only"); }
}
