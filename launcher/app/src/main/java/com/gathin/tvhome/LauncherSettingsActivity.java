package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Remote-friendly settings owned by the launcher, organized into tvOS-style sections. */
public final class LauncherSettingsActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private static final String STATE_SECTION = "current_section";
    private static final String STATE_LAST_CATEGORY = "last_category";
    private static final int BACKUP_EXPORT_REQUEST = 77;
    private static final int BACKUP_IMPORT_REQUEST = 78;
    private static final int[] SECTION_IDS = {
            R.id.sectionHome, R.id.sectionAppearance, R.id.sectionWidgets,
            R.id.sectionSoundRemote, R.id.sectionSystem, R.id.sectionAbout
    };

    private SharedPreferences choices;
    private ScrollView settingsScroll;
    private View categoriesContainer;
    private TextView settingsKicker;
    private TextView settingsTitle;
    private TextView settingsBackHint;
    private SettingsRowView categoryHome;
    private SettingsRowView categoryAppearance;
    private SettingsRowView categoryWidgets;
    private SettingsRowView categorySoundRemote;
    private SettingsRowView categorySystem;
    private SettingsRowView categoryAbout;
    private final TextView[] columnChoices = new TextView[4];
    private SettingsRowView animationsChoice;
    private SettingsRowView advancedChoice;
    private SettingsRowView soundsChoice;
    private SettingsSliderView soundsVolumeChoice;
    private SettingsRowView wallpaperChoice;
    private SettingsRowView rowsChoice;
    private SettingsRowView weatherChoice;
    private SettingsRowView idleClockChoice;
    private int rowsBelowDock;
    private SettingsRowView libraryChoice;
    private SettingsRowView statsChoice;
    private SettingsRowView continueChoice;
    private boolean continueWatching;
    private SettingsRowView followThemeChoice;
    private SettingsRowView checkUpdatesChoice;
    private SettingsRowView backupChoice;
    private SettingsRowView glowChoice;
    private boolean checkingUpdate;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;
    private boolean navigationSounds;
    private int navigationSoundVolume;
    private boolean appLibrary;
    private boolean showStats;
    private String wallpaper;
    private String glowStyle;
    private int glowIntensity;
    private int tileGlowIntensity;
    private int idleClockMinutes;
    private int currentSection;
    private int lastCategoryId = R.id.categoryHome;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        ((HomePreviewView) findViewById(R.id.settingsPreviewImage)).setDockPackage(getIntent().getStringExtra(HomePreviewView.DOCK_PACKAGE));
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        settingsScroll = findViewById(R.id.settingsScroll);
        categoriesContainer = findViewById(R.id.settingsCategoriesContainer);
        settingsKicker = findViewById(R.id.settingsKicker);
        settingsTitle = findViewById(R.id.settingsTitle);
        settingsBackHint = findViewById(R.id.settingsBackHint);
        loadConfigState();

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            LinearLayout row = (LinearLayout) columnChoices[0].getParent();
            row.removeAllViews();
            for (int i = columnChoices.length - 1; i >= 0; i--) row.addView(columnChoices[i]);
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (d, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                glass(dialog);
            }
        });
        soundsChoice = findViewById(R.id.soundsChoice);
        soundsChoice.setOnClickListener(v -> {
            navigationSounds = !navigationSounds;
            choices.edit().putBoolean("navigation_sounds", navigationSounds).apply();
            Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK);
            if (navigationSounds) {
                NavSound.setVolume(navigationSoundVolume);
                NavSound.play(true);
            }
            refresh();
        });
        soundsVolumeChoice = findViewById(R.id.soundsVolumeChoice);
        soundsVolumeChoice.setStepSoundEnabled(false);
        soundsVolumeChoice.bind(SettingsSliderView.DEFAULT_MIN, SettingsSliderView.DEFAULT_MAX,
                SettingsSliderView.DEFAULT_STEP, navigationSoundVolume, NavSound.DEFAULT_VOLUME, 0xFFFF9F0A, val -> {
                    navigationSoundVolume = val;
                    choices.edit().putInt(Config.NAV_SOUND_VOLUME_KEY, navigationSoundVolume).apply();
                    NavSound.setVolume(navigationSoundVolume);
                    if (navigationSounds && navigationSoundVolume > 0) NavSound.play(true);
                    Config.markSettingsChanged(LauncherSettingsActivity.this);
                    setResult(RESULT_OK);
                    categorySoundRemote.setValue(navigationSounds ? navigationSoundVolume + "%" : getString(R.string.off), true);
                });
        libraryChoice = findViewById(R.id.libraryChoice);
        libraryChoice.setOnClickListener(v -> {
            appLibrary = !appLibrary;
            choices.edit().putBoolean("app_library", appLibrary).apply();
            Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK);
            refresh();
        });
        continueChoice = findViewById(R.id.continueWatchingChoice);
        continueChoice.setIcon(R.drawable.ic_quick_screensaver, 0xFF64D2FF);
        continueChoice.setOnClickListener(v -> {
            if (!continueWatching && !ContinueWatchingRepository.permitted(this)) {
                requestPermissions(new String[]{ContinueWatchingRepository.PERMISSION}, 74);
            } else setContinueWatching(!continueWatching);
        });
        statsChoice = findViewById(R.id.statsChoice);
        statsChoice.setOnClickListener(v -> {
            showStats = !showStats;
            choices.edit().putBoolean("show_stats", showStats).apply();
            Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK);
            refresh();
        });
        rowsChoice = findViewById(R.id.rowsChoice);
        rowsChoice.setIcon(R.drawable.ic_quick_apps, 0xFF5E5CE6);
        rowsChoice.setOnClickListener(v -> {
            rowsBelowDock = (rowsBelowDock + 1) % 4;
            choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, rowsBelowDock).apply();
            Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK);
            refresh();
        });
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> openFeature(FeatureSettingsActivity.WALLPAPER));
        glowChoice = findViewById(R.id.glowChoice);
        glowChoice.setIcon(R.drawable.ic_quick_picture, 0xFFBF5AF2);
        glowChoice.setOnClickListener(v -> openFeature(FeatureSettingsActivity.GLOW));
        weatherChoice = featureRow(R.id.weatherChoice, FeatureSettingsActivity.WEATHER, R.drawable.ic_quick_picture, 0xFF64D2FF);
        idleClockChoice = featureRow(R.id.idleClockChoice, FeatureSettingsActivity.CLOCK, R.drawable.ic_quick_screensaver, 0xFFBF5AF2);
        featureRow(R.id.appCustomizationChoice, FeatureSettingsActivity.APPS, R.drawable.ic_apps, 0xFFBF5AF2);
        featureRow(R.id.remoteButtonsChoice, FeatureSettingsActivity.REMOTE, R.drawable.ic_quick_apps, 0xFF30D158);
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setVisibility(View.GONE);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects")
                    .remove("navigation_sounds").remove(Config.NAV_SOUND_VOLUME_KEY).remove("wallpaper_choice")
                    .remove("app_library").remove("show_stats").remove(Config.ROWS_BELOW_DOCK_KEY).commit();
            FeaturePreferences.clearAppearance(this);
            loadConfigState();
            Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> chooseDefaultHome());
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        wallpaperChoice.setIcon(R.drawable.ic_quick_screensaver, 0xFF0A84FF);
        animationsChoice.setIcon(R.drawable.ic_settings_motion, 0xFF30D158);
        advancedChoice.setIcon(R.drawable.ic_quick_launcher_settings, 0xFFFF9F0A);
        soundsChoice.setIcon(R.drawable.ic_quick_sound, 0xFFFF375F);
        soundsVolumeChoice.setIcon(R.drawable.ic_quick_sound, 0xFFFF9F0A);
        libraryChoice.setIcon(R.drawable.ic_apps, 0xFF0A84FF);
        statsChoice.setIcon(R.drawable.ic_settings_stats, 0xFF30D158);
        followThemeChoice.setIcon(R.drawable.ic_quick_launcher_settings, 0xFF64D2FF);
        SettingsRowView defaultHome = findViewById(R.id.defaultHomeChoice);
        defaultHome.setIcon(R.drawable.ic_home_settings, 0xFF30B85A);
        defaultHome.setValue("", true);
        SettingsRowView tvSettings = findViewById(R.id.tvSettingsChoice);
        SettingsRowView optimization = findViewById(R.id.optimizationChoice);
        optimization.setIcon(R.drawable.ic_settings_stats, 0xFF30D158);
        optimization.setValue("", true);
        optimization.setOnClickListener(v -> startActivity(new Intent(this, OptimizationActivity.class)));
        backupChoice = findViewById(R.id.backupChoice);
        backupChoice.setIcon(R.drawable.ic_settings_backup, 0xFF0A84FF);
        backupChoice.setOnClickListener(v -> showBackupDialog());
        tvSettings.setIcon(R.drawable.ic_settings, 0xFF737989);
        tvSettings.setValue("", true);
        ((TextView) findViewById(R.id.aboutVersionText)).setText(
                getString(R.string.about_launcher_version, installedVersion()));
        checkUpdatesChoice = findViewById(R.id.checkUpdatesChoice);
        checkUpdatesChoice.setIcon(R.drawable.ic_settings_update, 0xFF30D158);
        checkUpdatesChoice.setValue("", true);
        checkUpdatesChoice.setOnClickListener(v -> showUpdateCenter());
        SettingsRowView github = findViewById(R.id.githubChoice);
        github.setIcon(R.drawable.ic_settings_link, 0xFF5E5CE6);
        github.setValue("v1tal9pw", true);
        github.setOnClickListener(v -> openGitHub());

        // Top-level tvOS category entries
        categoryHome = setupCategory(R.id.categoryHome, R.id.sectionHome,
                R.drawable.ic_home_settings, 0xFF0A84FF);
        categoryAppearance = setupCategory(R.id.categoryAppearance, R.id.sectionAppearance,
                R.drawable.ic_quick_picture, 0xFFBF5AF2);
        categoryWidgets = setupCategory(R.id.categoryWidgets, R.id.sectionWidgets,
                R.drawable.ic_quick_screensaver, 0xFF64D2FF);
        categorySoundRemote = setupCategory(R.id.categorySoundRemote, R.id.sectionSoundRemote,
                R.drawable.ic_quick_sound, 0xFFFF375F);
        categorySystem = setupCategory(R.id.categorySystem, R.id.sectionSystem,
                R.drawable.ic_settings, 0xFF737989);
        categoryAbout = setupCategory(R.id.categoryAbout, R.id.sectionAbout,
                R.drawable.ic_settings_info, 0xFF5E5CE6);

        // White focus and a raised shadow stay within the row's width. No scaling into a clip edge.
        for (int id : new int[]{
                R.id.categoryHome, R.id.categoryAppearance, R.id.categoryWidgets,
                R.id.categorySoundRemote, R.id.categorySystem, R.id.categoryAbout,
                R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7, R.id.animationsChoice,
                R.id.advancedChoice, R.id.soundsChoice, R.id.soundsVolumeChoice,
                R.id.libraryChoice, R.id.statsChoice,
                R.id.continueWatchingChoice,
                R.id.wallpaperChoice, R.id.rowsChoice, R.id.glowChoice, R.id.weatherChoice, R.id.idleClockChoice,
                R.id.appCustomizationChoice, R.id.remoteButtonsChoice, R.id.followThemeChoice,
                R.id.defaultHomeChoice, R.id.tvSettingsChoice,
                R.id.checkUpdatesChoice, R.id.githubChoice, R.id.optimizationChoice, R.id.backupChoice}) {
            findViewById(id).setOnFocusChangeListener((v, focused) -> {
                v.animate().translationZ(focused ? 6 * getResources().getDisplayMetrics().density : 0)
                        .setDuration(animations ? 130 : 0).start();
                if (focused) showDetail(id);
            });
        }
        for (TextView column : columnChoices) column.setNextFocusDownId(R.id.rowsChoice);
        refresh();

        if (state != null) {
            lastCategoryId = state.getInt(STATE_LAST_CATEGORY, R.id.categoryHome);
            int savedSection = state.getInt(STATE_SECTION, 0);
            if (savedSection != 0) {
                openSection(savedSection, lastCategoryId, false);
                return;
            }
        }
        showCategories(false);
    }

    private void loadConfigState() {
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        navigationSounds = config.navigationSounds;
        navigationSoundVolume = config.navigationSoundVolume;
        NavSound.setVolume(navigationSoundVolume);
        if (navigationSounds) NavSound.prepare(this);
        appLibrary = config.appLibrary;
        showStats = config.showStats;
        continueWatching = config.continueWatching;
        wallpaper = config.wallpaperChoice;
        glowStyle = config.glowStyle;
        glowIntensity = config.glowIntensity;
        tileGlowIntensity = config.tileGlowIntensity;
        idleClockMinutes = config.homeIdleClockMinutes;
    }

    private SettingsRowView setupCategory(int categoryId, int sectionId, int icon, int color) {
        SettingsRowView row = findViewById(categoryId);
        row.setIcon(icon, color);
        row.setValue("", true);
        row.setOnClickListener(v -> openSection(sectionId, categoryId, true));
        return row;
    }

    private void openSection(int sectionId, int categoryId, boolean animate) {
        View targetSection = findViewById(sectionId);
        if (targetSection == null) return;
        currentSection = sectionId;
        lastCategoryId = categoryId;
        categoriesContainer.setVisibility(View.GONE);
        for (int id : SECTION_IDS) {
            findViewById(id).setVisibility(id == sectionId ? View.VISIBLE : View.GONE);
        }
        settingsScroll.scrollTo(0, 0);
        settingsKicker.setText(R.string.launcher_settings);
        settingsTitle.setText(titleForSection(sectionId));
        settingsBackHint.setVisibility(View.VISIBLE);
        if (animate && animations) {
            float shift = 16f * getResources().getDisplayMetrics().density;
            targetSection.animate().cancel();
            targetSection.setAlpha(0f);
            targetSection.setTranslationX(shift);
            targetSection.animate().alpha(1f).translationX(0f).setDuration(150).start();
        } else {
            targetSection.setAlpha(1f);
            targetSection.setTranslationX(0f);
        }
        View initialFocus = initialFocusForSection(sectionId);
        if (initialFocus != null) {
            showDetail(initialFocus.getId());
            initialFocus.post(initialFocus::requestFocus);
        }
    }

    private void showCategories(boolean animate) {
        currentSection = 0;
        for (int id : SECTION_IDS) findViewById(id).setVisibility(View.GONE);
        categoriesContainer.setVisibility(View.VISIBLE);
        settingsScroll.scrollTo(0, 0);
        settingsKicker.setText(R.string.launcher_brand);
        settingsTitle.setText(R.string.launcher_settings);
        settingsBackHint.setVisibility(View.GONE);
        if (animate && animations) {
            float shift = -14f * getResources().getDisplayMetrics().density;
            categoriesContainer.animate().cancel();
            categoriesContainer.setAlpha(0f);
            categoriesContainer.setTranslationX(shift);
            categoriesContainer.animate().alpha(1f).translationX(0f).setDuration(150).start();
        } else {
            categoriesContainer.setAlpha(1f);
            categoriesContainer.setTranslationX(0f);
        }
        View target = findViewById(lastCategoryId);
        if (target == null) target = categoryHome;
        showDetail(target.getId());
        View focus = target;
        focus.post(focus::requestFocus);
    }

    private int titleForSection(int sectionId) {
        if (sectionId == R.id.sectionHome) return R.string.settings_cat_home;
        if (sectionId == R.id.sectionAppearance) return R.string.settings_cat_appearance;
        if (sectionId == R.id.sectionWidgets) return R.string.settings_cat_widgets;
        if (sectionId == R.id.sectionSoundRemote) return R.string.settings_cat_sound_remote;
        if (sectionId == R.id.sectionSystem) return R.string.settings_cat_system;
        if (sectionId == R.id.sectionAbout) return R.string.settings_cat_about;
        return R.string.launcher_settings;
    }

    private View initialFocusForSection(int sectionId) {
        if (sectionId == R.id.sectionHome) return columnChoices[Math.max(0, Math.min(columns - 4, 3))];
        if (sectionId == R.id.sectionAppearance) return wallpaperChoice;
        if (sectionId == R.id.sectionWidgets) return weatherChoice;
        if (sectionId == R.id.sectionSoundRemote) return soundsChoice;
        if (sectionId == R.id.sectionSystem) return findViewById(R.id.defaultHomeChoice);
        if (sectionId == R.id.sectionAbout) return checkUpdatesChoice;
        return categoryHome;
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SECTION, currentSection);
        outState.putInt(STATE_LAST_CATEGORY, lastCategoryId);
    }

    @Override public void onBackPressed() {
        if (currentSection != 0) {
            showCategories(true);
            return;
        }
        super.onBackPressed();
    }

    /** The confirmation in Home's frosted dialog colours: dark rounded panel, white glass buttons. */
    private void glass(AlertDialog dialog) {
        float density = getResources().getDisplayMetrics().density;
        if (dialog.getWindow() != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(0xF2343840);
            panel.setCornerRadius(28 * density);
            dialog.getWindow().setBackgroundDrawable(panel);
            dialog.getWindow().setDimAmount(.32f);
        }
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            int pad = Math.round(18 * density), padV = Math.round(10 * density);
            button.setAllCaps(false);
            button.setTextSize(16);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setTextColor(getResources().getColorStateList(R.color.glass_action_text, getTheme()));
            button.setBackgroundResource(R.drawable.glass_menu_row);
            button.setPadding(pad, padV, pad, padV);
        }
        View message = dialog.findViewById(android.R.id.message);
        if (message instanceof TextView) ((TextView) message).setTextColor(0xD9FFFFFF);
        if (dialog.getWindow() != null) setSounds(dialog.getWindow().getDecorView());
        NavSound.attachDialog(dialog, () -> navigationSounds);
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        return NavSound.dispatchActivityKey(this, event, navigationSounds, () -> super.dispatchKeyEvent(event));
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setToggle(animations);
        advancedChoice.setToggle(advancedEffects);
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        soundsChoice.setToggle(navigationSounds);
        soundsVolumeChoice.setValue(navigationSoundVolume);
        soundsVolumeChoice.setEnabled(navigationSounds);
        soundsVolumeChoice.setAlpha(navigationSounds ? 1f : 0.5f);
        libraryChoice.setToggle(appLibrary);
        statsChoice.setToggle(showStats);
        continueChoice.setToggle(continueWatching);
        String wallpaperLabel = Config.WALLPAPER_AURORA.equals(wallpaper) ? getString(R.string.wallpaper_aurora)
                : Config.WALLPAPER_OFF.equals(wallpaper) ? getString(R.string.off)
                : Config.WALLPAPER_THEME.equals(wallpaper) ? getString(R.string.wallpaper_theme) : wallpaper;
        if (PersonalWallpapers.COAST_VIDEO.equals(wallpaper) || wallpaper.startsWith(PersonalWallpapers.PREFIX)) {
            wallpaperLabel = PersonalWallpapers.label(this, wallpaper);
        } else if (FeaturePreferences.SCENES.equals(wallpaper)) wallpaperLabel = getString(R.string.feature_scenes);
        else if (wallpaper.startsWith(FeaturePreferences.SCENE_PREFIX)) wallpaperLabel = SceneFiles.label(wallpaper.substring(FeaturePreferences.SCENE_PREFIX.length()));
        wallpaperChoice.setValue(wallpaperLabel, true);
        rowsChoice.setValue(String.valueOf(rowsBelowDock), true);
        glowChoice.setValue(glowSummary(), true);
        rowsChoice.setNextFocusUpId(columnChoices[Math.max(0, Math.min(columns - 4, 3))].getId());

        SharedPreferences features = FeaturePreferences.prefs(this);
        boolean weatherEnabled = features.getBoolean("weather_enabled", true);
        String city = features.getString("city", "");
        String shortCity = shortCityName(city);
        String weatherSummary = !weatherEnabled ? getString(R.string.off)
                : (!shortCity.isEmpty() ? shortCity : getString(R.string.on));
        weatherChoice.setValue(weatherSummary, true);
        String clockSummary = idleClockMinutes > 0
                ? getString(R.string.feature_minutes, idleClockMinutes)
                : getString(R.string.off);
        idleClockChoice.setValue(clockSummary, true);

        categoryHome.setValue(getString(R.string.settings_cat_home_value, columns), true);
        categoryAppearance.setValue(wallpaperLabel, true);
        categoryWidgets.setValue(weatherEnabled && !shortCity.isEmpty() ? shortCity
                : (idleClockMinutes > 0 ? clockSummary : ""), true);
        categorySoundRemote.setValue(navigationSounds ? navigationSoundVolume + "%" : getString(R.string.off), true);
        categorySystem.setValue("", true);
        if (backupChoice != null) {
            backupChoice.setValue(SettingsBackup.hasLocalBackup(this)
                    ? getString(R.string.settings_backup_saved_badge) : "", true);
        }
        ReleaseUpdater.Release pendingUpdate = ReleaseUpdater.cachedUpdate(this);
        boolean hasUpdate = pendingUpdate != null;
        categoryAbout.setValue(installedVersion(), true);
        categoryAbout.setBadge(hasUpdate);
        checkUpdatesChoice.setBadge(hasUpdate);
        if (!checkingUpdate) {
            checkUpdatesChoice.setValue(hasUpdate
                    ? getString(R.string.update_available_short, pendingUpdate.code) : "", true);
        }

        followThemeChoice.setSelected(!hasOnTvChoices());
        followThemeChoice.setValue(getString(hasOnTvChoices() ? R.string.settings_custom : R.string.wallpaper_theme), true);
        ((TextView) findViewById(R.id.settingsPreviewCaption)).setText(getString(R.string.wallpaper_state, wallpaperLabel));
        updatePreview();
        setSounds(getWindow().getDecorView());
    }

    private String glowSummary() {
        boolean bgActive = !Config.GLOW_OFF.equals(glowStyle) && glowIntensity > 0;
        if (!bgActive) {
            return tileGlowIntensity > 0
                    ? getString(R.string.feature_glow_tile_short, tileGlowIntensity)
                    : getString(R.string.off);
        }
        int label = Config.GLOW_EDGE.equals(glowStyle) ? R.string.feature_glow_edge
                : Config.GLOW_HORIZON.equals(glowStyle) ? R.string.feature_glow_horizon
                : R.string.feature_glow_radial;
        return getString(label) + " · " + glowIntensity + "%";
    }

    static String shortCityName(String city) {
        if (city == null) return "";
        int comma = city.indexOf(',');
        return (comma >= 0 ? city.substring(0, comma) : city).trim();
    }

    private void showDetail(int id) {
        int title, hint;
        if (id == R.id.categoryHome) {
            title = R.string.settings_cat_home; hint = R.string.settings_cat_home_hint;
        } else if (id == R.id.categoryAppearance) {
            title = R.string.settings_cat_appearance; hint = R.string.settings_cat_appearance_hint;
        } else if (id == R.id.categoryWidgets) {
            title = R.string.settings_cat_widgets; hint = R.string.settings_cat_widgets_hint;
        } else if (id == R.id.categorySoundRemote) {
            title = R.string.settings_cat_sound_remote; hint = R.string.settings_cat_sound_remote_hint;
        } else if (id == R.id.categorySystem) {
            title = R.string.settings_cat_system; hint = R.string.settings_cat_system_hint;
        } else if (id == R.id.categoryAbout) {
            title = R.string.settings_cat_about; hint = R.string.settings_cat_about_hint;
        } else if (id == R.id.optimizationChoice) {
            title = R.string.optimize_title; hint = R.string.optimize_help;
        } else if (id == R.id.backupChoice) {
            title = R.string.settings_backup_title; hint = R.string.settings_backup_hint;
        } else if (id == R.id.wallpaperChoice) {
            title = R.string.settings_wallpaper; hint = R.string.wallpaper_hint;
        } else if (id == R.id.rowsChoice) {
            title = R.string.rows_below_dock_label; hint = R.string.settings_rows_hint;
        } else if (id == R.id.glowChoice) {
            title = R.string.feature_glow; hint = R.string.feature_glow_help;
        } else if (id == R.id.weatherChoice) {
            title = R.string.feature_weather; hint = R.string.feature_weather_help;
        } else if (id == R.id.idleClockChoice) {
            title = R.string.feature_idle_clock; hint = R.string.feature_idle_help;
        } else if (id == R.id.appCustomizationChoice) {
            title = R.string.feature_apps; hint = R.string.feature_apps_help;
        } else if (id == R.id.remoteButtonsChoice) {
            title = R.string.feature_remote; hint = R.string.feature_remote_help;
        } else if (id == R.id.animationsChoice) {
            title = R.string.settings_animations; hint = R.string.settings_animations_hint;
        } else if (id == R.id.advancedChoice) {
            title = R.string.advanced_effects; hint = R.string.advanced_effects_warning;
        } else if (id == R.id.soundsChoice) {
            title = R.string.settings_sounds; hint = R.string.settings_sounds_hint;
        } else if (id == R.id.soundsVolumeChoice) {
            title = R.string.settings_sounds_volume; hint = R.string.settings_sounds_volume_hint;
        } else if (id == R.id.libraryChoice) {
            title = R.string.app_library; hint = R.string.settings_app_library_hint;
        } else if (id == R.id.statsChoice) {
            title = R.string.settings_stats; hint = R.string.settings_stats_hint;
        } else if (id == R.id.continueWatchingChoice) {
            title = R.string.continue_watching; hint = R.string.continue_watching_help;
        } else if (id == R.id.followThemeChoice) {
            title = R.string.settings_follow_theme; hint = R.string.follow_theme_hint;
        } else if (id == R.id.defaultHomeChoice) {
            title = R.string.home_settings; hint = R.string.home_settings_help;
        } else if (id == R.id.tvSettingsChoice) {
            title = R.string.tv_settings; hint = R.string.settings_system_hint;
        } else if (id == R.id.checkUpdatesChoice) {
            title = R.string.check_for_updates; hint = R.string.check_updates_hint;
        } else if (id == R.id.githubChoice) {
            ((TextView) findViewById(R.id.settingsDetailTitle)).setText(R.string.github_repository);
            ((TextView) findViewById(R.id.settingsDetailHint)).setText(
                    getString(R.string.github_hint, ReleaseUpdater.GITHUB_URL));
            return;
        } else {
            title = R.string.columns_label; hint = R.string.settings_columns_hint;
        }
        ((TextView) findViewById(R.id.settingsDetailTitle)).setText(title);
        ((TextView) findViewById(R.id.settingsDetailHint)).setText(hint);
    }

    private void updatePreview() {
        ((HomePreviewView) findViewById(R.id.settingsPreviewImage)).refresh(Config.load(this), null);
    }

    private String installedVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "?";
        }
    }

    private void showUpdateCenter() {
        ReleaseUpdater.Release pending = ReleaseUpdater.cachedUpdate(this);
        if (pending != null) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.update_available)
                    .setMessage(ReleaseUpdater.prompt(this, pending))
                    .setPositiveButton(R.string.update_install, (d, which) ->
                            startActivity(UpdateActivity.intent(this, pending)))
                    .setNeutralButton(R.string.update_recheck_button, (d, which) -> checkForUpdates())
                    .setNegativeButton(android.R.string.cancel, null).show();
            glass(dialog);
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.check_for_updates)
                .setMessage(getString(R.string.about_launcher_message, installedVersion())
                        + "\n\n" + getString(R.string.update_changes) + "\n" + getString(R.string.installed_changes))
                .setPositiveButton(R.string.update_check_button, (d, which) -> checkForUpdates())
                .setNegativeButton(android.R.string.cancel, null).show();
        glass(dialog);
    }

    private void checkForUpdates() {
        if (checkingUpdate) return;
        checkingUpdate = true;
        checkUpdatesChoice.setValue(getString(R.string.update_checking), false);
        ReleaseUpdater.checkNow(this, (release, checked) -> {
            if (isFinishing() || isDestroyed()) return;
            checkingUpdate = false;
            refresh();
            if (!checked) {
                String detail = ReleaseUpdater.lastError();
                String msg = getString(R.string.update_check_failed)
                        + (detail != null && !detail.isEmpty() ? " (" + detail + ")" : "");
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            } else if (release == null) {
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.update_current_title)
                        .setMessage(getString(R.string.update_current_message, installedVersion()))
                        .setPositiveButton(android.R.string.ok, null).show();
                glass(dialog);
            } else {
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.update_available)
                        .setMessage(ReleaseUpdater.prompt(this, release))
                        .setPositiveButton(R.string.update_install, (d, which) ->
                                startActivity(UpdateActivity.intent(this, release)))
                        .setNegativeButton(android.R.string.cancel, null).show();
                glass(dialog);
            }
        });
    }

    private void openGitHub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ReleaseUpdater.GITHUB_URL)));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.github_repository)
                    .setMessage(getString(R.string.github_open_failed, ReleaseUpdater.GITHUB_URL))
                    .setPositiveButton(android.R.string.ok, null).show();
            glass(dialog);
        }
    }

    private void setContinueWatching(boolean enabled) {
        continueWatching = enabled;
        FeaturePreferences.prefs(this).edit().putBoolean("continue_watching", enabled).apply();
        Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK); refresh();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 74) {
            if (grants.length > 0 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) setContinueWatching(true);
            else Toast.makeText(this, R.string.continue_permission_help, Toast.LENGTH_LONG).show();
        }
    }

    private void showBackupDialog() {
        boolean hasLocal = SettingsBackup.hasLocalBackup(this);
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<Runnable> actions = new java.util.ArrayList<>();
        labels.add(getString(R.string.settings_backup_export_tv));
        actions.add(this::exportBackupLocal);
        labels.add(getString(R.string.settings_backup_export_file));
        actions.add(this::exportBackupPicker);
        if (hasLocal) {
            labels.add(getString(R.string.settings_backup_restore_tv));
            actions.add(this::restoreBackupLocal);
        }
        labels.add(getString(R.string.settings_backup_restore_file));
        actions.add(this::restoreBackupPicker);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_backup_title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        glass(dialog);
    }

    private void exportBackupLocal() {
        try {
            java.io.File saved = SettingsBackup.exportToLocalFile(this);
            Toast.makeText(this, getString(R.string.settings_backup_export_ok, saved.getName()), Toast.LENGTH_LONG).show();
            refresh();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.settings_backup_failed,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void exportBackupPicker() {
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, SettingsBackup.DEFAULT_FILE_NAME);
        try {
            startActivityForResult(create, BACKUP_EXPORT_REQUEST);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(this, R.string.settings_backup_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void restoreBackupLocal() {
        try {
            SettingsBackup.restoreFromLocalFile(this);
            loadConfigState();
            setResult(RESULT_OK);
            refresh();
            Toast.makeText(this, R.string.settings_backup_restore_ok, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.settings_backup_failed,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void restoreBackupPicker() {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(open, BACKUP_IMPORT_REQUEST);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(this, R.string.settings_backup_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        loadConfigState();
        refresh();
        ReleaseUpdater.checkAuto(this, release -> {
            if (!isFinishing() && !isDestroyed()) refresh();
        });
    }

    private void setSounds(View view) {
        view.setSoundEffectsEnabled(navigationSounds && !(view instanceof SettingsSliderView));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setSounds(group.getChildAt(i));
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY) || choices.contains("animations")
                || choices.contains("advanced_effects") || choices.contains("navigation_sounds")
                || choices.contains(Config.NAV_SOUND_VOLUME_KEY)
                || choices.contains("wallpaper_choice") || choices.contains("app_library")
                || choices.contains("show_stats") || FeaturePreferences.hasAppearanceChoices(this);
    }

    private void chooseDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        boolean isDefault = resolved != null && resolved.activityInfo != null && getPackageName().equals(resolved.activityInfo.packageName);
        boolean fix = RemoteButtonMapper.homeFixEnabled(this);
        String status = getString(R.string.home_system_status, getString(isDefault ? R.string.on : R.string.off))
                + "\n" + getString(R.string.home_key_status, getString(fix && InputKeyService.connected ? R.string.on : R.string.off));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(status)
                .setItems(new String[]{getString(R.string.home_system_choose), getString(R.string.remote_home_fix)
                        + ": " + getString(fix ? R.string.on : R.string.off), getString(R.string.feature_remote_access)},
                        (d, which) -> {
                            if (which == 0) requestDefaultHome();
                            else if (which == 1) {
                                FeaturePreferences.prefs(this).edit().putBoolean(RemoteButtonMapper.HOME_FIX, !fix).apply();
                                if (!fix && !InputKeyService.connected) {
                                    Toast.makeText(this, R.string.remote_helper_required, Toast.LENGTH_LONG).show();
                                    open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                                } else chooseDefaultHome();
                            } else if (which == 2) open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        }).setNegativeButton(android.R.string.cancel, null).show();
        glass(dialog);
    }

    private void requestDefaultHome() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager roles = getSystemService(android.app.role.RoleManager.class);
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    Toast.makeText(this, R.string.home_already_default, Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 44);
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // Some TV firmware advertises a role without exposing its picker.
                }
            }
        } else {
            Intent picker = new Intent(Settings.ACTION_HOME_SETTINGS);
            if (picker.resolveActivity(getPackageManager()) != null) {
                open(picker);
                return;
            }
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.home_default_unavailable_title)
                .setMessage(R.string.home_default_unavailable)
                .setPositiveButton(android.R.string.ok, null).show();
        glass(dialog);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private void openFeature(String page) {
        startActivityForResult(FeatureSettingsActivity.intent(this, page)
                .putExtra(HomePreviewView.DOCK_PACKAGE, getIntent().getStringExtra(HomePreviewView.DOCK_PACKAGE)), 43);
    }

    private SettingsRowView featureRow(int id, String page, int icon, int color) {
        SettingsRowView row = findViewById(id); row.setIcon(icon, color); row.setValue("", true);
        row.setOnClickListener(v -> openFeature(page));
        return row;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 43 && resultCode == RESULT_OK) {
            loadConfigState();
            Config.markSettingsChanged(LauncherSettingsActivity.this); setResult(RESULT_OK); refresh();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == BACKUP_EXPORT_REQUEST) {
            try {
                SettingsBackup.exportToUri(this, uri);
                Toast.makeText(this, R.string.settings_backup_export_uri_ok, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.settings_backup_failed,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == BACKUP_IMPORT_REQUEST) {
            try {
                SettingsBackup.restoreFromUri(this, uri);
                loadConfigState();
                setResult(RESULT_OK);
                refresh();
                Toast.makeText(this, R.string.settings_backup_restore_ok, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.settings_backup_failed,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), Toast.LENGTH_LONG).show();
            }
        }
    }
}
