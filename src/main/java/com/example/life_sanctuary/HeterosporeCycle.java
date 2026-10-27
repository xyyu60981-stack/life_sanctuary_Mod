package com.example.life_sanctuary;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import static com.example.life_sanctuary.HeterosporeGametophyteRules.*;

/** Immutable active-tick timeline. The male's three-to-five-day lifetime starts after maturity. */
public record HeterosporeCycle(Kind kind, boolean mature, long age, long directTarget, long maleLifetime,
        int attempts, Outcome outcome, long terminalTarget) {
    public enum Outcome { ACTIVE, FERTILIZED, FAILED }
    public enum Action { NONE, MATURE, SEARCH, SEEDLING, DISAPPEAR }
    public HeterosporeCycle {
        java.util.Objects.requireNonNull(kind); java.util.Objects.requireNonNull(outcome);
        if (age < 0 || age > 144_000 || attempts < 0 || attempts > 3
                || kind == Kind.MIXED && (directTarget < MIXED_MIN_TICKS || directTarget > MIXED_MAX_TICKS)
                || kind != Kind.MIXED && directTarget != 0
                || kind != Kind.MALE && maleLifetime != 0
                || kind == Kind.MALE && maleLifetime != 0 && (maleLifetime < 72_000 || maleLifetime > 120_000)
                || !mature && (age > MATURITY_TICKS || attempts != 0 || outcome != Outcome.ACTIVE)
                || mature && age < MATURITY_TICKS || mature && kind == Kind.MALE && maleLifetime == 0
                || kind == Kind.MALE && (attempts != 0 || outcome != Outcome.ACTIVE)
                || outcome == Outcome.ACTIVE && terminalTarget != 0
                || outcome == Outcome.ACTIVE && kind == Kind.FEMALE && attempts == 3
                || outcome != Outcome.ACTIVE && (!mature || attempts == 0 || kind == Kind.MALE)
                || outcome == Outcome.FAILED && (kind != Kind.FEMALE || attempts != 3))
            throw new IllegalArgumentException("Invalid heterospore cycle");
        long searchAge = MATURITY_TICKS + (long) attempts * SEARCH_INTERVAL;
        if (mature && age < searchAge
                || outcome == Outcome.FERTILIZED && (terminalTarget < searchAge + 12_000 || terminalTarget > searchAge + 24_000)
                || outcome == Outcome.FAILED && (terminalTarget < searchAge + 1_000 || terminalTarget > searchAge + 3_000)
                || age > deadline(kind, mature, directTarget, maleLifetime, attempts, outcome, terminalTarget))
            throw new IllegalArgumentException("Invalid heterospore deadline");
    }
    public static HeterosporeCycle start(Kind kind, RandomSource random) {
        return new HeterosporeCycle(kind, false, 0, kind == Kind.MIXED ? mixedDelay(random) : 0, 0, 0, Outcome.ACTIVE, 0);
    }
    private static long deadline(Kind kind, boolean mature, long direct, long maleLife, int attempts, Outcome outcome, long terminal) {
        if (!mature) return MATURITY_TICKS;
        if (outcome != Outcome.ACTIVE) return kind == Kind.MIXED ? Math.min(direct, terminal) : terminal;
        if (kind == Kind.MALE) return MATURITY_TICKS + maleLife;
        long search = attempts < 3 ? MATURITY_TICKS + (attempts + 1L) * SEARCH_INTERVAL : Long.MAX_VALUE;
        return kind == Kind.MIXED ? Math.min(direct, search) : search;
    }
    public long remaining() { return deadline(kind, mature, directTarget, maleLifetime, attempts, outcome, terminalTarget) - age; }
    public HeterosporeCycle advance(long elapsed) {
        if (elapsed < 0 || elapsed > remaining()) throw new IllegalArgumentException("Cycle cannot skip an event");
        return new HeterosporeCycle(kind, mature, age + elapsed, directTarget, maleLifetime, attempts, outcome, terminalTarget);
    }
    public Action action() {
        if (remaining() != 0) return Action.NONE;
        if (!mature) return Action.MATURE;
        if (kind == Kind.MALE || outcome == Outcome.FAILED) return Action.DISAPPEAR;
        if (outcome == Outcome.FERTILIZED || kind == Kind.MIXED && age == directTarget) return Action.SEEDLING;
        return Action.SEARCH;
    }
    public HeterosporeCycle finishMaturity(RandomSource random) {
        if (action() != Action.MATURE) throw new IllegalStateException("Maturity is not due");
        long life = kind == Kind.MALE && maleLifetime == 0 ? HeterosporeGametophyteRules.maleLifetime(random) : maleLifetime;
        return new HeterosporeCycle(kind, true, age, directTarget, life, attempts, outcome, terminalTarget);
    }
    public HeterosporeCycle searchResult(boolean success, RandomSource random) {
        if (action() != Action.SEARCH) throw new IllegalStateException("Search is not due");
        int next = attempts + 1;
        var phase = success ? Outcome.FERTILIZED : disappearAfterFailure(kind, next) ? Outcome.FAILED : Outcome.ACTIVE;
        long target = success ? age + fertilizedDelay(random) : phase == Outcome.FAILED ? age + failureDelay(random) : 0;
        return new HeterosporeCycle(kind, mature, age, directTarget, maleLifetime, next, phase, target);
    }
    /** Pruning clears progress, retaining each already sampled target, including a male's lifespan. */
    public HeterosporeCycle pruned() { return new HeterosporeCycle(kind, false, 0, directTarget, maleLifetime, 0, Outcome.ACTIVE, 0); }
    public boolean provides() { return mature && kind != Kind.FEMALE && (kind != Kind.MALE || remaining() > 0); }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putString("Kind", kind.name()); tag.putBoolean("Mature", mature); tag.putLong("Age", age);
        tag.putLong("Direct", directTarget); tag.putLong("MaleLife", maleLifetime); tag.putInt("Attempts", attempts);
        tag.putString("Outcome", outcome.name()); tag.putLong("Terminal", terminalTarget); return tag;
    }
    public static HeterosporeCycle read(CompoundTag tag) {
        if (!tag.contains("Kind", Tag.TAG_STRING) || !tag.contains("Mature", Tag.TAG_BYTE) || !tag.contains("Age", Tag.TAG_LONG)
                || !tag.contains("Direct", Tag.TAG_LONG) || !tag.contains("MaleLife", Tag.TAG_LONG) || !tag.contains("Attempts", Tag.TAG_INT)
                || !tag.contains("Outcome", Tag.TAG_STRING) || !tag.contains("Terminal", Tag.TAG_LONG)) throw new IllegalArgumentException("Incomplete cycle");
        return new HeterosporeCycle(Kind.valueOf(tag.getString("Kind")), tag.getBoolean("Mature"), tag.getLong("Age"), tag.getLong("Direct"),
                tag.getLong("MaleLife"), tag.getInt("Attempts"), Outcome.valueOf(tag.getString("Outcome")), tag.getLong("Terminal"));
    }
}
