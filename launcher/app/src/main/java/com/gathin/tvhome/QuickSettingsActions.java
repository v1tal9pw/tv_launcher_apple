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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collection;
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
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.apple.atve.androidtv.appletv",
            "com.spotify.tv.android",
            "tv.twitch.android.app"
    };
    private static final Pattern SAFE_PACKAGE = Pattern.compile("[a-zA-Z0-9._]+");
    private static final AtomicBoolean ADB_CLEANING = new AtomicBoolean(false);

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
        clearBackground(context, null, null);
    }

    static void clearBackground(Context context, String activeHostPackage, Runnable onNeedAdbSetup) {
        Context localized = LauncherLocale.wrap(context);
        if (!ADB_CLEANING.compareAndSet(false, true)) {
            Toast.makeText(localized, R.string.quick_cleanup_running, Toast.LENGTH_SHORT).show();
            return;
        }
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(localized, R.string.quick_cleanup_running, Toast.LENGTH_SHORT).show();
        Set<String> excluded = collectExcludedCleanupPackages(app, activeHostPackage);
        String command = buildAdbCleanupCommand(excluded);
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
                    Toast.makeText(localized, message, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                String reason = error.getMessage() == null ? "" : error.getMessage();
                main.post(() -> {
                    if (reason.contains("ADB_PORT_5555_CLOSED")) {
                        Toast.makeText(localized, R.string.quick_cleanup_adb_closed, Toast.LENGTH_LONG).show();
                        if (onNeedAdbSetup != null) onNeedAdbSetup.run();
                    } else if (reason.contains("ADB_NOT_AUTHORIZED") || reason.contains("ADB_AUTH_FAILED")) {
                        Toast.makeText(localized, R.string.quick_cleanup_adb_auth, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(localized, localized.getString(R.string.quick_cleanup_adb_error,
                                reason.isEmpty() ? error.getClass().getSimpleName() : reason), Toast.LENGTH_LONG).show();
                    }
                });
            } finally {
                ADB_CLEANING.set(false);
            }
        });
    }

    private static Set<String> collectExcludedCleanupPackages(Context context, String activeHostPackage) {
        Set<String> excluded = new LinkedHashSet<>();
        excluded.add(context.getPackageName());
        if (activeHostPackage != null && !activeHostPackage.isEmpty()) excluded.add(activeHostPackage);
        try {
            String vpn = Config.load(context).vpnApp;
            if (vpn != null && !vpn.isEmpty()) excluded.add(vpn);
        } catch (RuntimeException ignored) { }
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
        try {
            List<ResolveInfo> vpns = context.getPackageManager().queryIntentServices(
                    new Intent("android.net.VpnService"), 0);
            for (ResolveInfo info : vpns) {
                if (info != null && info.serviceInfo != null && info.serviceInfo.packageName != null) {
                    excluded.add(info.serviceInfo.packageName);
                }
            }
        } catch (RuntimeException ignored) { }
        return excluded;
    }

    static String buildAdbCleanupCommand(Collection<String> excludedPackages) {
        StringBuilder skip = new StringBuilder(" ");
        if (excludedPackages != null) {
            for (String pkg : excludedPackages) {
                if (pkg != null && SAFE_PACKAGE.matcher(pkg).matches()) skip.append(pkg).append(' ');
            }
        }
        StringBuilder media = new StringBuilder();
        for (String pkg : MEDIA_CLEANUP_PACKAGES) media.append(' ').append(pkg);
        return "before=0; "
                + "while read k v _; do [ \"$k\" = \"MemAvailable:\" ] && before=$v && break; done < /proc/meminfo; "
                + "running=\" $(ps -A -o NAME 2>/dev/null | tr '\\n' ' ') \"; "
                + "skip=\"" + skip + "\"; "
                + "stopped=0; "
                + "for p in $(pm list packages -3 --user 0 2>/dev/null | sed 's/^package://')" + media + "; do "
                + "case \"$skip\" in *\" $p \"*) continue ;; esac; "
                + "skip=\"$skip$p \"; "
                + "case \"$running\" in *\" $p \"*|*\" $p:\"*) am force-stop \"$p\" >/dev/null 2>&1 && stopped=$((stopped+1)) ;; esac; "
                + "done; "
                + "am kill-all >/dev/null 2>&1; "
                + "pm trim-caches 999999999999 >/dev/null 2>&1; "
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
        String standardAction = control == R.id.quickPicture ? Settings.ACTION_DISPLAY_SETTINGS
                : control == R.id.quickSound ? Settings.ACTION_SOUND_SETTINGS : Settings.ACTION_SETTINGS;
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
