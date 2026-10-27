package com.example.life_sanctuary;

import java.util.Optional;

/** One manual stroke, no saved progress or resource debit before the endpoint. */
public final class FossilSlicingRules {
    private FossilSlicingRules() {}
    public static final int END = 64, MAXIMUM_STEP = 8;
    public static final int BEGIN = 0, MOVE_BASE = 1, CANCEL = 66;
    public record FeedPlan(int remainingInput, int workCount) {}
    public static Optional<FeedPlan> planFeed(int inputCount, int workCount, boolean validInput) {
        if (!validInput || inputCount <= 0 || workCount != 0) return Optional.empty();
        return Optional.of(new FeedPlan(inputCount - 1, 1));
    }
    public static boolean canSlice(int workCount, boolean validRaw) { return workCount == 1 && validRaw; }
    public record Stroke(int position, long lastTick, boolean active) {
        public Stroke {
            if (position < 0 || position > END || lastTick < 0 || active && position == END)
                throw new IllegalArgumentException("Invalid fossil slicing stroke");
        }
        public static Stroke begin(long tick) { return new Stroke(0, tick, true); }
        public Optional<Stroke> move(int destination, long tick) {
            if (!active || destination < 0 || destination > END || destination == position || tick <= lastTick
                    || Math.abs(destination - position) > MAXIMUM_STEP) return Optional.empty();
            return Optional.of(new Stroke(destination, tick, destination != END));
        }
        public boolean complete() { return !active && position == END; }
    }
}
