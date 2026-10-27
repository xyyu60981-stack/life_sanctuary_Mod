package com.example.life_sanctuary;

/** Row boundaries never wrap horizontally; a short final row keeps the nearest valid column. */
public final class HandheldNavigation {
    private HandheldNavigation() {}
    public static int move(int selected, int count, int columns, int dx, int dy) {
        if (count < 0 || columns < 1 || Math.abs((long) dx) + Math.abs((long) dy) != 1)
            throw new IllegalArgumentException("Invalid app navigation");
        if (count == 0) return -1;
        int current = Math.max(0, Math.min(count - 1, selected));
        int row = current / columns, col = current % columns;
        if (dx != 0) {
            long destination = (long) current + dx;
            return col + dx < 0 || col + dx >= columns || destination >= count ? current : (int) destination;
        }
        long nextRow = (long) row + dy;
        if (nextRow < 0 || nextRow > (count - 1) / columns) return current;
        return (int) Math.min((long) count - 1, nextRow * columns + col);
    }
}
