package dev.beamcraft;

import java.util.Locale;

/** Accumulates time spent per section of Minecraft's frame loop (render thread only). */
public final class FrameTiming {
    public static final int FLIP = 0, LIMIT = 1, GAME = 2;
    private static final long[] total = new long[3];
    private static final long[] started = new long[3];
    private static long frames;

    private FrameTiming() {}

    public static void begin(int section) {
        started[section] = System.nanoTime();
    }

    public static void end(int section) {
        total[section] += System.nanoTime() - started[section];
        if (section == FLIP) frames++;
    }

    /** Average ms per frame for present / frame limiter / game render, then reset. */
    public static String summary() {
        double n = Math.max(1, frames);
        String s = String.format(Locale.ROOT, "present %.1f ms, limiter %.1f ms, render %.1f ms per frame",
                total[FLIP] / 1e6 / n, total[LIMIT] / 1e6 / n, total[GAME] / 1e6 / n);
        total[0] = total[1] = total[2] = 0;
        frames = 0;
        return s;
    }
}
