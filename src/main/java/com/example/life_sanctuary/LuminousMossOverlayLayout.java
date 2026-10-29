package com.example.life_sanctuary;

/** Stable per-face appearance; independent of growth stage, frame time and gameplay random streams. */
public final class LuminousMossOverlayLayout {
    private LuminousMossOverlayLayout() {}
    public static int variants(long basePosition) {
        long value = basePosition + 0x9e3779b97f4a7c15L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return (int) (value ^ (value >>> 31)) & 15;
    }
    public static int code(int exposedFaces, long basePosition) {
        if (exposedFaces < 0 || exposedFaces > 15) throw new IllegalArgumentException("Four side bits required");
        return (variants(basePosition) << 4) | exposedFaces;
    }
}
