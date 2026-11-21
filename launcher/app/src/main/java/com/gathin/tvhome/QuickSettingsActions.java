package com.gathin.tvhome;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Resolve optional configured firmware destinations, then fall back to public Android settings. */
final class QuickSettingsActions {
    static final String SYSTEM_CLEANER = "com.tcl.guard";
    static final String ACTION_CONNECTED_DEVICES = "com.android.tv.settings.CONNECTED_DEVICES";
    private static final String ACCESSORIES_SLICE_AUTHORITY = "com.android.tv.settings.accessories.sliceprovider";
    private static final String ACCESSORIES_SLICE_URI = "content://" + ACCESSORIES_SLICE_AUTHORITY + "/general";
    private static final String[] VENDOR_BLUETOOTH_COMPONENTS = {
            "com.tcl.settings/.accessories.AccessoriesActivity",
            "com.tcl.settings/com.tcl.settings.accessories.AccessoriesActivity",
            "com.tcl.settings/.bluetooth.BluetoothActivity",
            "com.tcl.settings/com.tcl.settings.bluetooth.BluetoothActivity"
    };
    private static final String[] MEDIA_CLEANUP_PACKAGES = {
            "com.google.android.youtube.tv",
            "com.google.android.youtube.tvmusic",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.apple.atve.androidtv.appletv",
            "com.spotify.tv.android",
            "tv.twitch.android.app",
            "com.google.android.play.games",
            "com.android.vending"
    };
    private static final Set<String> CORE_SYSTEM_PACKAGES = new LinkedHashSet<>(java.util.Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.tv.settings",
            "com.google.android.tv.settings",
            "com.tcl.settings",
            "com.xiaomi.mitv.settings",
            "com.tcl.tv",
            "com.tcl.tvinput",
            "com.tcl.suspension",
            "com.tcl.systemui.plugin",
            "com.tcl.globalkeyoverlay",
            "com.android.tv",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.tungsten.setupwraith",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.tv.remote.service",
            "com.google.android.apps.mediashell"
    ));
    private static final Pattern SAFE_PACKAGE = Pattern.compile("[a-zA-Z0-9._]+");
    private static final AtomicBoolean ADB_CLEANING = new AtomicBoolean(false);

    interface CleanupFeedback {
        void onStatus(String message, boolean finished);
        default void onNeedAdbSetup() { }
    }

    static final class CleanupResult {
        final int stoppedApps;
        final int freedMb;

        CleanupResult(int stoppedApps, int freedMb) {
            this.stoppedApps = stoppedApps;
            this.freedMb = freedMb;
        }
    }

    static boolean nativeCleanerEnabled(int flags, boolean enabled) {
        return enabled && (flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    static Intent nativeCleaner(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo app = pm.getApplicationInfo(SYSTEM_CLEANER, 0);
            if (!nativeCleanerEnabled(app.flags, app.enabled)) return null;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(SYSTEM_CLEANER);
            if (launch != null && available(context, launch) != null) return launch;
            launch = pm.getLaunchIntentForPackage(SYSTEM_CLEANER);
            return launch != null ? available(context, launch) : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return null; }
    }

    static void clearBackground(Context context) {
        clearBackground(context, null, (Runnable) null);
    }

    static void clearBackground(Context context, String activeHostPackage, Runnable onNeedAdbSetup) {
        Context localized = LauncherLocale.wrap(context);
        clearBackground(context, activeHostPackage, new CleanupFeedback() {
            @Override public void onStatus(String message, boolean finished) {
                Toast.makeText(localized, message, finished ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }

            @Override public void onNeedAdbSetup() {
                if (onNeedAdbSetup != null) onNeedAdbSetup.run();
            }
        });
    }

    static void clearBackground(Context context, String activeHostPackage, CleanupFeedback feedback) {
        Context localized = LauncherLocale.wrap(context);
        if (!ADB_CLEANING.compareAndSet(false, true)) {
            if (feedback != null) feedback.onStatus(localized.getString(R.string.quick_cleanup_running), false);
            return;
        }
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (feedback != null) feedback.onStatus(localized.getString(R.string.quick_cleanup_running), false);
        Set<String> excluded = collectExcludedCleanupPackages(app, activeHostPackage);
        Set<String> vpnFallback = isVpnConnected(app) ? collectVpnPackages(app) : Collections.emptySet();
        Set<String> extraApps = collectExtraCleanupPackages(app, excluded);
        String command = buildAdbCleanupCommand(excluded, extraApps, vpnFallback);
        FeatureNetwork.WORKER.execute(() -> {
            try {
                String output = LocalAdbClient.run(app, command);
                CleanupResult result = parseCleanupOutput(output);
                main.post(() -> {
                    String message;
                    if (result.stoppedApps > 0 && result.freedMb > 0) {
                        message = localized.getString(R.string.quick_cleanup_done_full,
                                result.stoppedApps, result.freedMb);
                    } else if (result.stoppedApps > 0) {
                        message = localized.getString(R.string.quick_cleanup_done_apps, result.stoppedApps);
                    } else if (result.freedMb > 0) {
                        message = localized.getString(R.string.quick_cleanup_done_ram, result.freedMb);
                    } else {
                        message = localized.getString(R.string.quick_cleanup_done_cache);
                    }
                    if (feedback != null) feedback.onStatus(message, true);
                });
            } catch (Exception error) {
                String reason = error.getMessage() == null ? "" : error.getMessage();
                main.post(() -> {
                    if (reason.contains("ADB_PORT_5555_CLOSED")) {
                        if (feedback != null) {
                            feedback.onStatus(localized.getString(R.string.quick_cleanup_adb_closed), true);
                            feedback.onNeedAdbSetup();
                        }
                    } else if (reason.contains("ADB_NOT_AUTHORIZED") || reason.contains("ADB_AUTH_FAILED")) {
                        if (feedback != null) {
                            feedback.onStatus(localized.getString(R.string.quick_cleanup_adb_auth), true);
                        }
                    } else {
                        if (feedback != null) {
                            feedback.onStatus(localized.getString(R.string.quick_cleanup_adb_error,
                                    reason.isEmpty() ? error.getClass().getSimpleName() : reason), true);
                        }
                    }
                });
            } finally {
                ADB_CLEANING.set(false);
            }
        });
    }

    static boolean isVpnConnected(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
                }
            }
        } catch (RuntimeException ignored) { }
        try {
            for (NetworkInterface net : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!net.isUp() || net.isLoopback()) continue;
                String name = net.getName();
                if (name != null && (name.startsWith("tun") || name.startsWith("wg") || name.startsWith("ppp"))) {
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static Set<String> collectVpnPackages(Context context) {
        Set<String> vpns = new LinkedHashSet<>();
        try {
            String vpn = Config.load(context).vpnApp;
            if (vpn != null && !vpn.isEmpty()) vpns.add(vpn);
        } catch (RuntimeException ignored) { }
        try {
            List<ResolveInfo> services = context.getPackageManager().queryIntentServices(
                    new Intent("android.net.VpnService"), 0);
            for (ResolveInfo info : services) {
                if (info != null && info.serviceInfo != null && info.serviceInfo.packageName != null) {
                    vpns.add(info.serviceInfo.packageName);
                }
            }
        } catch (RuntimeException ignored) { }
        return vpns;
    }

    private static Set<String> collectExcludedCleanupPackages(Context context, String activeHostPackage) {
        Set<String> excluded = new LinkedHashSet<>();
        excluded.add(context.getPackageName());
        if (activeHostPackage != null && !activeHostPackage.isEmpty()
                && !CORE_SYSTEM_PACKAGES.contains(activeHostPackage)) {
            excluded.add(activeHostPackage);
        }
        try {
            InputMethodManager imm = context.getSystemService(InputMethodManager.class);
            if (imm != null) {
                for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                    if (info != null && info.getPackageName() != null) excluded.add(info.getPackageName());
                }
            }
        } catch (RuntimeException ignored) { }
        try {
            AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
            if (am != null) {
                for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    if (info != null && info.getResolveInfo() != null
                            && info.getResolveInfo().serviceInfo != null
                            && info.getResolveInfo().serviceInfo.packageName != null) {
                        excluded.add(info.getResolveInfo().serviceInfo.packageName);
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return excluded;
    }

    private static Set<String> collectExtraCleanupPackages(Context context, Set<String> excluded) {
        Set<String> extras = new LinkedHashSet<>();
        for (String pkg : MEDIA_CLEANUP_PACKAGES) {
            if (!excluded.contains(pkg) && !CORE_SYSTEM_PACKAGES.contains(pkg)) extras.add(pkg);
        }
        try {
            PackageManager pm = context.getPackageManager();
            String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String category : categories) {
                List<ResolveInfo> activities = pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
                for (ResolveInfo info : activities) {
                    if (info == null || info.activityInfo == null || info.activityInfo.applicationInfo == null) continue;
                    ApplicationInfo appInfo = info.activityInfo.applicationInfo;
                    // Third-party apps are already listed via `pm list packages -3` in the shell script;
                    // only add pre-installed/system launchable apps here so the ADB packet stays small.
                    if ((appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                        continue;
                    }
                    String pkg = info.activityInfo.packageName;
                    if (pkg == null || excluded.contains(pkg) || CORE_SYSTEM_PACKAGES.contains(pkg)) continue;
                    extras.add(pkg);
                    if (extras.size() >= 24) return extras;
                }
            }
        } catch (RuntimeException ignored) { }
        return extras;
    }

    static String buildAdbCleanupCommand(Collection<String> excludedPackages) {
        return buildAdbCleanupCommand(excludedPackages, java.util.Arrays.asList(MEDIA_CLEANUP_PACKAGES), null);
    }

    static String buildAdbCleanupCommand(Collection<String> excludedPackages,
                                         Collection<String> extraPackages,
                                         Collection<String> vpnFallbackPackages) {
        StringBuilder skip = new StringBuilder(" ");
        if (excludedPackages != null) {
            for (String pkg : excludedPackages) {
                if (pkg != null && SAFE_PACKAGE.matcher(pkg).matches()) skip.append(pkg).append(' ');
            }
        }
        StringBuilder vpnFallback = new StringBuilder();
        if (vpnFallbackPackages != null) {
            for (String pkg : vpnFallbackPackages) {
                if (pkg != null && SAFE_PACKAGE.matcher(pkg).matches()) vpnFallback.append(pkg).append(' ');
            }
        }
        StringBuilder extra = new StringBuilder();
        if (extraPackages != null) {
            for (String pkg : extraPackages) {
                if (pkg != null && SAFE_PACKAGE.matcher(pkg).matches()) extra.append(' ').append(pkg);
            }
        }
        return "before=0; "
                + "while read k v _; do [ \"$k\" = \"MemAvailable:\" ] && before=$v && break; done < /proc/meminfo; "
                + "fg=$(dumpsys activity activities 2>/dev/null | sed -nE '/(topResumedActivity=|ResumedActivity:)/{s/.* u[0-9]+ ([a-zA-Z0-9._]+)\\/.*/\\1/p;q}'); "
                + "[ -z \"$fg\" ] && fg=$(dumpsys window 2>/dev/null | sed -nE '/mCurrentFocus=/{s/.* ([a-zA-Z0-9._]+)\\/.*/\\1/p;q}'); "
                + "vpn=$(dumpsys vpn_management 2>/dev/null | sed -nE 's/.*Active package name:[[:space:]]*([a-zA-Z0-9._]+)[[:space:]]*$/\\1/p'); "
                + "[ -z \"$vpn\" ] && vpn=$(dumpsys connectivity 2>/dev/null | sed -nE 's/.*mPackage=([a-zA-Z0-9._]+).*/\\1/p'); "
                + "skip=\"" + skip + "\"; "
                + "[ -n \"$fg\" ] && skip=\"$skip$fg \"; "
                + "if [ -n \"$vpn\" ]; then for v in $vpn; do skip=\"$skip$v \"; done; else skip=\"$skip" + vpnFallback + "\"; fi; "
                + "running=\" $(ps -A -o NAME= 2>/dev/null | tr '\\n' ' ') \"; "
                + "[ \"$running\" = \"  \" ] && running=\" $(ps -A -o NAME 2>/dev/null | tr '\\n' ' ') \"; "
                + "stopped=0; "
                + "for p in $(pm list packages -3 --user 0 2>/dev/null | sed 's/^package://')" + extra + "; do "
                + "case \"$skip\" in *\" $p \"*) continue ;; esac; "
                + "skip=\"$skip$p \"; "
                + "case \"$running\" in *\" $p \"*|*\" $p:\"*) am force-stop \"$p\" >/dev/null 2>&1 && stopped=$((stopped+1)) ;; esac; "
                + "done; "
                + "am kill-all >/dev/null 2>&1; "
                + "if command -v timeout >/dev/null 2>&1; then timeout 5 pm trim-caches 999999999999 >/dev/null 2>&1; "
                + "else pm trim-caches 999999999999 >/dev/null 2>&1; fi; "
                + "after=0; "
                + "while read k v _; do [ \"$k\" = \"MemAvailable:\" ] && after=$v && break; done < /proc/meminfo; "
                + "echo \"BEFORE=$before\"; echo \"AFTER=$after\"; echo \"STOPPED=$stopped\"";
    }

    static CleanupResult parseCleanupOutput(String output) {
        long beforeKb = 0L, afterKb = 0L;
        int stopped = 0;
        if (output != null) {
            for (String raw : output.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.startsWith("BEFORE=")) beforeKb = parseLongSafe(line.substring(7));
                else if (line.startsWith("AFTER=")) afterKb = parseLongSafe(line.substring(6));
                else if (line.startsWith("STOPPED=")) stopped = (int) Math.max(0L, parseLongSafe(line.substring(8)));
            }
        }
        int freedMb = afterKb > beforeKb ? (int) ((afterKb - beforeKb) / 1024L) : 0;
        return new CleanupResult(stopped, freedMb);
    }

    private static long parseLongSafe(String value) {
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    /**
     * Resolves "Remotes & Accessories" for the Bluetooth control. If the TV exposes the Google TV
     * Remotes & Accessories slice behind WRITE_SECURE_SETTINGS and local ADB is active, grants the
     * permission once so SliceActivity opens directly on this and all subsequent clicks.
     */
    static void resolveBluetooth(Context context, java.util.function.Consumer<Intent> onReady) {
        Map<String, String> routes = Config.load(context).settingsRoutes;
        Intent direct = directBluetoothIntent(context, routes);
        if (direct != null) {
            onReady.accept(direct);
            return;
        }
        Intent protectedSlice = protectedBluetoothSlice(context, routes);
        if (protectedSlice == null) {
            onReady.accept(systemIntent(context, Settings.ACTION_SETTINGS, routes));
            return;
        }
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        FeatureNetwork.WORKER.execute(() -> {
            try {
                LocalAdbClient.run(app, "pm grant " + app.getPackageName()
                        + " " + Manifest.permission.WRITE_SECURE_SETTINGS);
            } catch (Exception ignored) { }
            Intent chosen = app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED
                    ? protectedSlice
                    : systemIntent(app, Settings.ACTION_SETTINGS, routes);
            main.post(() -> onReady.accept(chosen));
        });
    }

    static Intent intent(Context context, int control) {
        return intent(context, control, Config.load(context).settingsRoutes);
    }

    static Intent intent(Context context, int control, Map<String, String> routes) {
        if (control == R.id.quickAccessibility)
            return systemIntent(context, Settings.ACTION_ACCESSIBILITY_SETTINGS, routes);
        if (control == R.id.quickAudioOutput) {
            Intent output = component(context, routes.get("quick_audio_output_component"));
            if (output != null) return output;
            Intent slice = settingsSlice(context, routes, "quick_audio_output_slice_resource");
            if (slice != null) return slice;
            return systemIntent(context, Settings.ACTION_SOUND_SETTINGS, routes);
        }
        if (control == R.id.quickApps) {
            Intent apps = component(context, routes.get("quick_apps_component"));
            if (apps != null) return apps;
            return systemIntent(context, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, routes);
        }
        if (control == R.id.quickNetwork)
            return systemIntent(context, Settings.ACTION_WIFI_SETTINGS, routes);
        if (control == R.id.quickBluetooth) {
            // Open Remotes & Accessories (never the standalone pairing-search AddAccessoryActivity).
            Intent direct = directBluetoothIntent(context, routes);
            if (direct != null) return direct;
            return systemIntent(context, Settings.ACTION_SETTINGS, routes);
        }
        if (control == R.id.quickScreensaver) {
            Intent dream = action(context, routes.get("quick_screensaver_action"));
            if (dream != null) return dream;
            Intent settings = action(context, Settings.ACTION_DREAM_SETTINGS);
            if (settings != null) return settings;
            settings = component(context, routes.get("quick_screensaver_component"));
            if (settings != null) return settings;
            return systemIntent(context, Settings.ACTION_SETTINGS, routes);
        }
        if (control == R.id.quickSound) {
            // On TCL Google TV 14 (e.g. C6K), DISPLAY_SETTINGS opens the native TV Display & Sound side
            // drawer (Picture / Screen / Sound / Audio output) over apps, while SOUND_SETTINGS opens the
            // AOSP TvSettings Display & Sound screen (text scaling / system sounds). Prefer DISPLAY_SETTINGS
            // when the firmware provides it, and fall back to SOUND_SETTINGS on generic Android TV.
            Intent display = action(context, Settings.ACTION_DISPLAY_SETTINGS);
            if (display != null) return display;
            return systemIntent(context, Settings.ACTION_SOUND_SETTINGS, routes);
        }
        String standardAction = control == R.id.quickPicture ? Settings.ACTION_DISPLAY_SETTINGS
                : Settings.ACTION_SETTINGS;
        return systemIntent(context, standardAction, routes);
    }

    static Intent systemIntent(Context context, String standardAction, Map<String, String> routes) {
        Intent chosen=chooseSystemIntent(standardAction,routes,intent -> available(context,intent)!=null);
        if(available(context,chosen)!=null) return chosen;
        for(String value:context.getResources().getStringArray(R.array.settings_fallback_components)){
            Intent candidate=component(context,value);if(candidate!=null)return candidate;
        }
        Intent aosp=component(context,"com.android.tv.settings/.MainSettings");
        return aosp!=null?aosp:chosen;
    }

    static Intent chooseSystemIntent(String standardAction, Map<String, String> routes,
                                     Predicate<Intent> canLaunch) {
        for (String candidate : relatedActions(standardAction)) {
            Intent standard = new Intent(candidate);
            if (canLaunch.test(standard)) return standard;
        }
        Intent general = new Intent(Settings.ACTION_SETTINGS);
        if (canLaunch.test(general)) return general;
        Intent fallback = componentIntent(routes.get("settings_fallback_component"));
        if (fallback != null && canLaunch.test(fallback)) return fallback;
        // The caller catches ActivityNotFoundException/SecurityException and shows Not available.
        return new Intent(standardAction);
    }

    /** Firmware can expose one of several public entry points for the same category. */
    private static String[] relatedActions(String requested) {
        if (Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS.equals(requested))
            return new String[]{requested, Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
                    Settings.ACTION_APPLICATION_SETTINGS};
        if (Settings.ACTION_WIFI_SETTINGS.equals(requested) || Settings.ACTION_BLUETOOTH_SETTINGS.equals(requested))
            return new String[]{requested, Settings.ACTION_WIRELESS_SETTINGS};
        return new String[]{requested};
    }

    private static Intent action(Context context, String action) {
        return action == null || action.isEmpty() ? null : available(context, new Intent(action));
    }

    private static Intent component(Context context, String value) {
        Intent intent = componentIntent(value);
        return intent != null ? available(context, intent) : null;
    }

    private static Intent componentIntent(String value) {
        if (value == null || value.isEmpty()) return null;
        ComponentName component = ComponentName.unflattenFromString(value);
        if (component == null) return null;
        return new Intent().setComponent(component);
    }

    private static Intent available(Context context, Intent intent) {
        try {
            ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved != null && !resolved.activityInfo.name.contains("CTSDummy")
                    && launchable(context, resolved.activityInfo) ? intent : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static boolean launchable(Context context, ActivityInfo info) {
        return info != null && info.enabled && info.exported
                && info.applicationInfo != null && info.applicationInfo.enabled
                && (info.permission == null || info.permission.isEmpty()
                || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED);
    }

    private static Intent directBluetoothIntent(Context context, Map<String, String> routes) {
        Intent accessories = component(context, routes.get("quick_bluetooth_component"));
        if (accessories != null) return accessories;
        Intent connectedDevices = action(context, ACTION_CONNECTED_DEVICES);
        if (connectedDevices != null) return connectedDevices;
        Intent slice = bluetoothSlice(context, routes, true);
        if (slice != null) return slice;
        Intent bluetooth = action(context, Settings.ACTION_BLUETOOTH_SETTINGS);
        if (bluetooth != null) return bluetooth;
        for (String vendor : VENDOR_BLUETOOTH_COMPONENTS) {
            Intent candidate = component(context, vendor);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static Intent protectedBluetoothSlice(Context context, Map<String, String> routes) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED) return null;
        return bluetoothSlice(context, routes, false);
    }

    private static Intent bluetoothSlice(Context context, Map<String, String> routes, boolean requirePermission) {
        Intent configured = settingsSlice(context, routes, "quick_bluetooth_slice_resource", requirePermission);
        if (configured != null) return configured;
        // On Android TV / Google TV 12-15 (e.g. TCL C6K Google TV 14), TvSettings may expose
        // ConnectedDevicesSliceProvider directly even when connected_devices_slice_uri is unpopulated.
        if (Build.VERSION.SDK_INT > 35) return null;
        String pkg = routes.get("settings_package");
        String action = routes.get("settings_slice_action");
        String extra = routes.get("settings_slice_uri_extra");
        if (pkg == null || pkg.isEmpty() || action == null || action.isEmpty() || extra == null || extra.isEmpty())
            return null;
        try {
            if (context.getPackageManager().resolveContentProvider(ACCESSORIES_SLICE_AUTHORITY, 0) == null)
                return null;
            for (String candidate : settingsPackages(context, pkg)) {
                Intent slice = new Intent(action).setPackage(candidate).putExtra(extra, ACCESSORIES_SLICE_URI);
                if (sliceResolvable(context, slice, requirePermission)) return slice;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static Intent settingsSlice(Context context, Map<String, String> routes, String key) {
        return settingsSlice(context, routes, key, true);
    }

    private static Intent settingsSlice(Context context, Map<String, String> routes, String key,
                                        boolean requirePermission) {
        String pkg = routes.get("settings_package");
        String resource = routes.get(key);
        String action = routes.get("settings_slice_action");
        String extra = routes.get("settings_slice_uri_extra");
        if (pkg == null || pkg.isEmpty() || resource == null || resource.isEmpty()
                || action == null || action.isEmpty() || extra == null || extra.isEmpty()) return null;
        // Keep the configured package first, then discover the firmware's public Settings handlers.
        // Vendor builds can use their own settings package rather than the AOSP package name.
        for (String candidate : settingsPackages(context, pkg)) {
            try {
                android.content.res.Resources res = context.getPackageManager().getResourcesForApplication(candidate);
                int id = res.getIdentifier(resource, "string", candidate);
                Uri uri = id == 0 ? null : Uri.parse(res.getString(id));
                if (uri != null && "content".equals(uri.getScheme())
                        && uri.getAuthority() != null && !uri.getAuthority().isEmpty()) {
                    Intent slice = new Intent(action).setPackage(candidate).putExtra(extra, uri.toString());
                    if (sliceResolvable(context, slice, requirePermission)) return slice;
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                // A different settings handler may expose this optional resource and slice protocol.
            }
        }
        return null;
    }

    private static boolean sliceResolvable(Context context, Intent slice, boolean requirePermission) {
        if (requirePermission) return available(context, slice) != null;
        try {
            ResolveInfo resolved = context.getPackageManager().resolveActivity(slice, PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo info = resolved == null ? null : resolved.activityInfo;
            return info != null && info.enabled && info.exported
                    && !info.name.contains("CTSDummy")
                    && info.applicationInfo != null && info.applicationInfo.enabled
                    && Manifest.permission.WRITE_SECURE_SETTINGS.equals(info.permission);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static List<String> settingsPackages(Context context, String configured) {
        List<ResolveInfo> handlers = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Intent settings = new Intent(Settings.ACTION_SETTINGS);
            ResolveInfo preferred = pm.resolveActivity(settings, PackageManager.MATCH_DEFAULT_ONLY);
            if (preferred != null) handlers.add(preferred);
            handlers.addAll(pm.queryIntentActivities(settings, PackageManager.MATCH_DEFAULT_ONLY));
        } catch (RuntimeException unavailable) {
            // Keep the configured destination when discovery is unavailable or restricted.
        }
        return settingsPackages(configured, handlers, info -> launchable(context, info));
    }

    static List<String> settingsPackages(String configured, List<ResolveInfo> handlers,
                                         Predicate<ActivityInfo> canLaunch) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        if (configured != null && !configured.isEmpty()) packages.add(configured);
        for (ResolveInfo handler : handlers) {
            ActivityInfo info = handler == null ? null : handler.activityInfo;
            if (info != null && info.applicationInfo != null && info.packageName != null
                    && !info.packageName.isEmpty() && canLaunch.test(info)
                    && (info.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                packages.add(info.packageName);
        }
        return new ArrayList<>(packages);
    }

    private QuickSettingsActions() { }
}
