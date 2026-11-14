package com.gathin.tvhome;

/** Tap selection used by the archive's 1x/2x/3x mapping workflow. */
final class TapSequence {
    static int maximum(String single, String twice, String triple) {
        return !triple.isEmpty() ? 3 : !twice.isEmpty() ? 2 : !single.isEmpty() ? 1 : 0;
    }

    static String target(int count, String single, String twice, String triple) {
        if (count >= 3 && !triple.isEmpty()) return triple;
        if (count >= 2 && !twice.isEmpty()) return twice;
        return single;
    }
}
