package com.gathin.tvhome;

final class OptimizationPolicy {
    static final String[] PACKAGES = {"com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher", "com.google.android.tvrecommendations"};
    static boolean allowed(String pkg) {
        for (String candidate : PACKAGES) if (candidate.equals(pkg)) return true;
        return false;
    }
    static boolean launcher(String pkg) {
        return allowed(pkg) && !pkg.equals("com.google.android.tvrecommendations");
    }
    private OptimizationPolicy() { }
}
