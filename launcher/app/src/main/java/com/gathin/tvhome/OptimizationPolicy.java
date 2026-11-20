package com.gathin.tvhome;

final class OptimizationPolicy {
    // Gathin23/cleanyourtv, examples/tcl-65c6k-google-tv/debloat.txt (MIT).
    static final String[] TCL_C6K_PACKAGES = {
            "com.tcl.logkit", "com.tcl.usercenter", "com.google.android.feedback",
            "com.android.tv.feedbackconsent", "com.android.adservices.api",
            "com.android.ondevicepersonalization.services", "com.android.federatedcompute.services",
            "com.tcl.channelplus", "com.tcl.tv.tclhome_passive", "com.tcl.partnercustomizer",
            "com.tcl.interactive", "com.tcl.messagebox", "com.tcl.waterfall.overseas",
            "com.tcl.dashboard", "android.autoinstalls.config.google.gtvpai",
            "com.google.android.partnersetup", "com.tcl.exhibit", "com.tcl.esticker",
            "com.tcl.hotelmenu", "com.tcl.ocean.instructions", "com.tcl.repairguide",
            "com.tcl.smartalexa", "com.tcl.guard", "com.tcl.UpdatePeripheral",
            "com.google.android.play.games", "com.google.android.youtube.tvmusic",
            "com.google.android.katniss"};
    static final String[] PACKAGES = {"com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
            "com.tcl.tv.launcher", "com.tcl.launcher",
            "com.falcon.launcher", "com.ffalcon.launcher", "com.google.android.tvrecommendations",
            "com.tcl.logkit", "com.tcl.usercenter", "com.tcl.channelplus",
            "com.tcl.interactive", "com.tcl.messagebox", "com.tcl.waterfall.overseas"};
    static boolean allowed(String pkg) {
        for (String candidate : PACKAGES) if (candidate.equals(pkg)) return true;
        return tclCandidate(pkg);
    }
    static boolean tclCandidate(String pkg) {
        for (String candidate : TCL_C6K_PACKAGES) if (candidate.equals(pkg)) return true;
        return false;
    }
    static boolean tclC6k(String model, String device, String product) {
        return (model + " " + device + " " + product).replace('\n', ' ').replace('\r', ' ')
                .toLowerCase(java.util.Locale.ROOT)
                .matches(".*\\b(c6k|[0-9]{2}c6k|g08)(\\b|_).*");
    }
    static java.util.List<String> candidates(boolean tclC6k) {
        return tclC6k ? new java.util.ArrayList<>(java.util.Arrays.asList(TCL_C6K_PACKAGES))
                : new java.util.ArrayList<>();
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
