package com.gathin.tvhome;

import android.content.Context;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the existing guarded cleanup flow through an explicitly paired local ADB connection. */
final class LocalOptimizationService {
    private static final String SEP = "__TVHOME_SEP__";
    private final Context context;
    private final Map<String, Integer> prepared = new HashMap<>();
    private boolean tclProfile;

    LocalOptimizationService(Context context) { this.context = context.getApplicationContext(); }

    private String run(String... args) throws Exception { return LocalAdbClient.run(context, String.join(" ", args)); }

    private void shellUser() throws Exception {
        String reply = LocalAdbClient.run(context,
                "id -u; echo " + SEP + "; am get-current-user; echo " + SEP
                        + "; getprop ro.product.model; echo " + SEP
                        + "; getprop ro.product.device; echo " + SEP + "; getprop ro.product.name");
        String[] parts = reply.split(SEP, -1);
        if (parts.length < 5 || !"2000".equals(parts[0].trim()) || !"0".equals(parts[1].trim()))
            throw new IllegalStateException("ADB_USER_REQUIRED");
        tclProfile = OptimizationPolicy.tclC6k(parts[2].trim(), parts[3].trim(), parts[4].trim());
    }

    private int state(String pkg) throws Exception {
        if (!OptimizationPolicy.allowed(pkg)) throw new SecurityException("PROTECTED_PACKAGE");
        if (OptimizationPolicy.tclCandidate(pkg)
                && !java.util.Arrays.asList(OptimizationPolicy.PACKAGES).contains(pkg)
                && !tclProfile)
            throw new SecurityException("TCL_C6K_PROFILE_REQUIRED");
        Matcher match = Pattern.compile("User 0:[^\\r\\n]*enabled=(\\d+)")
                .matcher(run("dumpsys", "package", pkg));
        if (!match.find()) throw new IllegalStateException("PACKAGE_STATE_UNAVAILABLE");
        return Integer.parseInt(match.group(1));
    }

    private void homeGuard() throws Exception {
        if (run("pm", "list", "packages", "com.cvte.").contains("package:com.cvte."))
            throw new IllegalStateException("BOARD_REQUIRES_MANUAL_SETUP");
        String home = run("cmd", "package", "resolve-activity", "--brief", "--user", "0",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME");
        if (!home.contains(context.getPackageName() + "/"))
            throw new IllegalStateException("SET_DEFAULT_HOME_FIRST");
        String activities = run("dumpsys", "activity", "activities");
        boolean foreground = false;
        for (String line : activities.split("\n"))
            if ((line.contains("mResumedActivity") || line.contains("topResumedActivity"))
                    && line.contains(context.getPackageName() + "/")) foreground = true;
        if (!foreground) throw new IllegalStateException("OPEN_LAUNCHER_FIRST");
    }

    synchronized String inventory() {
        try {
            shellUser(); JSONArray list = new JSONArray();
            String reply = LocalAdbClient.run(context,
                    "pm list packages --user 0; echo " + SEP + "; pm list packages -d --user 0");
            String[] parts = reply.split(SEP, -1);
            java.util.Set<String> installed = new java.util.HashSet<>(
                    java.util.Arrays.asList(parts[0].trim().split("\\s+")));
            java.util.Set<String> disabled = parts.length > 1
                    ? new java.util.HashSet<>(java.util.Arrays.asList(parts[1].trim().split("\\s+")))
                    : java.util.Collections.emptySet();
            for (String pkg : OptimizationPolicy.candidates(tclProfile)) {
                String entry = "package:" + pkg;
                if (installed.contains(entry))
                    list.put(new JSONObject().put("package", pkg).put("state", disabled.contains(entry) ? 3 : 0));
            }
            return list.toString();
        } catch (Exception e) { throw failure(e); }
    }

    synchronized String prepare(String pkg) {
        try {
            shellUser(); int before = state(pkg);
            if (before != 0 && before != 1) throw new IllegalStateException("ALREADY_DISABLED");
            if (OptimizationPolicy.launcher(pkg)) homeGuard();
            JSONObject backup = new JSONObject().put("package", pkg).put("state", before)
                    .put("time", System.currentTimeMillis())
                    .put("packages", run("pm", "list", "packages", "--user", "0"))
                    .put("disabled", run("pm", "list", "packages", "-d", "--user", "0"))
                    .put("properties", run("getprop"))
                    .put("secure", run("settings", "list", "secure"))
                    .put("system", run("settings", "list", "system"))
                    .put("global", run("settings", "list", "global"));
            prepared.put(pkg, before);
            return backup.toString();
        } catch (Exception e) { throw failure(e); }
    }

    synchronized void freeze(String pkg) {
        try {
            shellUser(); Integer expected = prepared.remove(pkg);
            if (expected == null || expected != state(pkg)) throw new IllegalStateException("PREPARE_REQUIRED");
            if (OptimizationPolicy.launcher(pkg)) homeGuard();
            run("pm", "disable-user", "--user", "0", pkg);
            if (state(pkg) != 3) throw new IllegalStateException("DISABLE_REFUSED");
        } catch (Exception e) { throw failure(e); }
    }

    synchronized void restore(String pkg, int originalState) {
        try {
            shellUser(); state(pkg);
            if (originalState != 0 && originalState != 1) throw new SecurityException("INVALID_ORIGINAL_STATE");
            run("pm", originalState == 0 ? "default-state" : "enable", "--user", "0", pkg);
            if (state(pkg) != originalState) throw new IllegalStateException("RESTORE_REFUSED");
        } catch (Exception e) { throw failure(e); }
    }

    private static IllegalStateException failure(Exception error) {
        return new IllegalStateException(error.getMessage() == null ? "OPERATION_FAILED" : error.getMessage());
    }

    void destroy() { prepared.clear(); }
}
