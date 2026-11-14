package com.gathin.tvhome;

final class OptimizationPolicy {
    static final String[] PACKAGES = {"com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
            "com.tcl.tv.launcher", "com.tcl.launcher",
            "com.falcon.launcher", "com.ffalcon.launcher", "com.google.android.tvrecommendations",
            "com.tcl.logkit", "com.tcl.usercenter", "com.tcl.channelplus",
            "com.tcl.interactive", "com.tcl.messagebox", "com.tcl.waterfall.overseas"};
    static boolean allowed(String pkg) {
        for (String candidate : PACKAGES) if (candidate.equals(pkg)) return true;
        return false;
    }
    static boolean launcher(String pkg) {
        return "com.google.android.apps.tv.launcherx".equals(pkg)
                || "com.google.android.tvlauncher".equals(pkg)
                || "com.google.android.leanbacklauncher".equals(pkg)
                || "com.tcl.tv.launcher".equals(pkg)
                || "com.tcl.launcher".equals(pkg) || "com.falcon.launcher".equals(pkg)
                || "com.ffalcon.launcher".equals(pkg);
    }
    private OptimizationPolicy() { }
}
