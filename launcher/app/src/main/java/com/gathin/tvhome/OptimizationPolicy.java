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
    static int guidance(String pkg) {
        if (pkg == null) return R.string.optimize_hint_unknown;
        switch (pkg) {
            case "com.tcl.logkit": return R.string.optimize_hint_logs;
            case "com.tcl.usercenter": return R.string.optimize_hint_account;
            case "com.google.android.feedback":
            case "com.android.tv.feedbackconsent": return R.string.optimize_hint_feedback;
            case "com.android.adservices.api": return R.string.optimize_hint_ads;
            case "com.android.ondevicepersonalization.services":
            case "com.android.federatedcompute.services": return R.string.optimize_hint_personalization;
            case "com.tcl.channelplus":
            case "com.tcl.waterfall.overseas": return R.string.optimize_hint_channels;
            case "com.tcl.tv.tclhome_passive": return R.string.optimize_hint_home_promos;
            case "com.tcl.partnercustomizer": return R.string.optimize_hint_home_rows;
            case "com.tcl.interactive": return R.string.optimize_hint_interactive;
            case "com.tcl.messagebox": return R.string.optimize_hint_messages;
            case "com.tcl.dashboard": return R.string.optimize_hint_dashboard;
            case "android.autoinstalls.config.google.gtvpai": return R.string.optimize_hint_partner_apps;
            case "com.google.android.partnersetup": return R.string.optimize_hint_partner_setup;
            case "com.tcl.exhibit":
            case "com.tcl.esticker": return R.string.optimize_hint_retail;
            case "com.tcl.hotelmenu": return R.string.optimize_hint_hotel;
            case "com.tcl.ocean.instructions":
            case "com.tcl.repairguide": return R.string.optimize_hint_guides;
            case "com.tcl.smartalexa": return R.string.optimize_hint_alexa;
            case "com.tcl.guard": return R.string.optimize_hint_cleaner;
            case "com.tcl.UpdatePeripheral": return R.string.optimize_hint_peripheral;
            case "com.google.android.play.games": return R.string.optimize_hint_games;
            case "com.google.android.youtube.tvmusic": return R.string.optimize_hint_music;
            case "com.google.android.katniss": return R.string.optimize_hint_assistant;
            default: return R.string.optimize_hint_unknown;
        }
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
