package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.net.URLEncoder;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Focusable, grouped glass settings for features that previously required editing a theme by hand. */
public final class FeatureSettingsActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    static final String PAGE = "feature_page";
    static final String WALLPAPER = "wallpaper", GLOW = "glow", WEATHER = "weather", APPS = "apps", REMOTE = "remote", CLOCK = "clock";
    private SharedPreferences features, choices;
    private LinearLayout rows, group;
    private TextView title, summary;
    private String page, editingPackage, imagePackage, selectedKey;
    private Config config;
    private boolean sounds, changed, busy;
    private boolean confirmPressed;
    private AlertDialog activeDialog;
    private int request;
    private int pageHelp;

    static Intent intent(Context context, String page) {
        return new Intent(context, FeatureSettingsActivity.class).putExtra(PAGE, page);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_feature_settings);
        ((HomePreviewView) findViewById(R.id.featurePreview)).setDockPackage(getIntent().getStringExtra(HomePreviewView.DOCK_PACKAGE));
        page = getIntent().getStringExtra(PAGE);
        if (page == null) page = WALLPAPER;
        if (state != null) {
            editingPackage = state.getString("editing_package");
            imagePackage = state.getString("image_package");
            changed = state.getBoolean("changed");
            if (changed) Config.markSettingsChanged(FeatureSettingsActivity.this); setResult(RESULT_OK);
        }
        features = FeaturePreferences.prefs(this);
        choices = getSharedPreferences(Config.SETTINGS_NAME, 0);
        rows = findViewById(R.id.featureRows); title = findViewById(R.id.featureTitle); summary = findViewById(R.id.featureSummary);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        rows.postDelayed(() -> { if (!isFinishing() && REMOTE.equals(page)) render(); }, 600);
    }
    @Override protected void onPause() { RemoteLearning.stop(); request++; busy = false; confirmPressed = false; super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("editing_package", editingPackage); state.putString("image_package", imagePackage);
        state.putBoolean("changed", changed); super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if (editingPackage != null) { editingPackage = null; selectedKey = null; render(); }
        else super.onBackPressed();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        if (confirm) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) confirmPressed = true;
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean pressed = confirmPressed; confirmPressed = false;
                // A few TV firmwares forward the opening row's release into the new activity.
                // It must not activate the newly focused first row as well.
                if (!pressed || event.isCanceled()) return true;
            }
        }
        if (RemoteLearning.handle(event)) return true;
        return NavSound.dispatchActivityKey(this, event, sounds, () -> super.dispatchKeyEvent(event));
    }

    private void render() {
        if (WALLPAPER.equals(page) && PersonalWallpapers.pruneMissing(this) > 0) {
            markChanged();
            Toast.makeText(this, R.string.personal_wallpaper_source_gone, Toast.LENGTH_SHORT).show();
        }
        config = Config.load(this); sounds = config.navigationSounds;
        if (sounds) NavSound.prepare(this);
        rows.removeAllViews(); group = null;
        switch (page) {
            case GLOW: glow(); break;
            case WEATHER: weather(); break;
            case APPS: apps(); break;
            case REMOTE: remote(); break;
            case CLOCK: clock(); break;
            default: wallpapers();
        }
        updatePreview();
        View target = selectedKey == null ? null : rows.findViewWithTag(selectedKey);
        if (target == null) target = firstFocusable(rows);
        if (target != null) { View focus = target; rows.post(focus::requestFocus); }
    }

    private void heading(int label, int help) { title.setText(label); pageHelp = help; summary.setText(help); }
    private void section(int label) {
        TextView text = new TextView(this); text.setText(label); text.setTextColor(0x80FFFFFF); text.setTextSize(11);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); text.setAllCaps(true);
        text.setPadding(dp(18), group == null ? 0 : dp(22), 0, dp(8));
        rows.addView(text, new LinearLayout.LayoutParams(-1, -2));
        group = new LinearLayout(this); group.setOrientation(LinearLayout.VERTICAL); group.setPadding(dp(4), dp(4), dp(4), dp(4));
        group.setBackgroundResource(R.drawable.settings_group_bg); group.setClipChildren(false); group.setClipToPadding(false);
        rows.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    private SettingsRowView row(String key, int label, String value, int icon, int color, Runnable action) {
        return row(key, getString(label), value, icon, color, action);
    }
    private SettingsRowView row(String key, String label, String value, int icon, int color, Runnable action) {
        if (group.getChildCount() > 0) {
            View divider = new View(this); divider.setBackgroundColor(0x14FFFFFF);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, Math.max(1, dp(.5f)));
            line.setMarginStart(dp(62)); line.setMarginEnd(dp(16)); group.addView(divider, line);
        }
        SettingsRowView view = (SettingsRowView) getLayoutInflater().inflate(R.layout.feature_settings_row, group, false);
        view.setId(View.generateViewId()); view.setTag(key); view.setText(label); view.setValue(value, true); view.setIcon(icon, color);
        view.setSoundEffectsEnabled(sounds); view.setOnClickListener(v -> action.run());
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) { selectedKey = key; if (!busy) summary.setText(pageHelp); }
        });
        group.addView(view); return view;
    }
    private void toggle(String key, int label, boolean value, int icon, int color, int help) {
        SettingsRowView row = row(key, label, "", icon, color, () -> {
            features.edit().putBoolean(key, !value).apply(); markChanged(); render();
        });
        row.setToggle(value);
        if (help != 0) row.setOnFocusChangeListener((v, focused) -> { if (focused) { selectedKey = key; summary.setText(help); } });
    }
    private void markChanged() { changed = true; Config.markSettingsChanged(FeatureSettingsActivity.this); setResult(RESULT_OK); }
    private SettingsSliderView slider(String key, int label, int icon, int iconColor, int accentColor,
                                      int value, int defaultValue, int helpRes, IntConsumer onChanged) {
        if (group.getChildCount() > 0) {
            View divider = new View(this); divider.setBackgroundColor(0x14FFFFFF);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, Math.max(1, dp(.5f)));
            line.setMarginStart(dp(62)); line.setMarginEnd(dp(16)); group.addView(divider, line);
        }
        SettingsSliderView view = new SettingsSliderView(this);
        view.setId(View.generateViewId());
        view.setTag(key);
        view.setLabel(getString(label));
        view.setIcon(icon, iconColor);
        view.setStepSoundEnabled(sounds);
        view.setSoundEffectsEnabled(false);
        view.bind(SettingsSliderView.DEFAULT_MIN, SettingsSliderView.DEFAULT_MAX,
                SettingsSliderView.DEFAULT_STEP, value, defaultValue, accentColor, next -> {
                    onChanged.accept(next);
                    config = Config.load(this);
                    updatePreview();
                    markChanged();
                });
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                selectedKey = key;
                if (!busy) summary.setText(helpRes != 0 ? helpRes : pageHelp);
            }
        });
        group.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private void integerChoice(String key, int label, int current, int[] values, String[] labels, int icon) {
        int checked = -1; for (int i = 0; i < values.length; i++) if (values[i] == current) checked = i;
        final int selected = checked;
        row(key, label, checked < 0 ? String.valueOf(current) : labels[checked], icon, 0xFF0A84FF,
                () -> choose(getString(label), labels, selected, which -> {
                    features.edit().putInt(key, values[which]).apply(); markChanged(); render();
                }));
    }

    private void wallpapers() {
        heading(R.string.feature_wallpapers, R.string.feature_wallpaper_help); section(R.string.feature_wallpapers);
        String value = wallpaperLabel(config.wallpaperChoice);
        row("wallpaper_source", R.string.feature_source, value, R.drawable.ic_quick_screensaver, 0xFF0A84FF, this::chooseWallpaper);
        row("personal_photo", R.string.personal_wallpaper_photo, "", R.drawable.ic_quick_picture, 0xFF0A84FF,
                () -> choosePersonalWallpaper(false));
        row("personal_video", R.string.personal_wallpaper_video, "", R.drawable.ic_quick_screensaver, 0xFF0A84FF,
                () -> choosePersonalWallpaper(true));
        List<File> personalFiles = PersonalWallpapers.listExisting(this);
        if (!personalFiles.isEmpty()) {
            row("personal_delete", R.string.personal_wallpaper_delete, String.valueOf(personalFiles.size()),
                    R.drawable.ic_quick_close, 0xFFFF453A, this::deletePersonalWallpaper);
        }
        row("personal_help", R.string.personal_wallpaper_limits, "", R.drawable.ic_quick_picture, 0xFF0A84FF,
                () -> message(R.string.personal_wallpaper_help));
        int[] minutes = {0, 1, 5, 15, 30, 60, 120};
        String[] rotationLabels = minuteLabels(minutes);
        int rotationSelected = -1;
        for (int i = 0; i < minutes.length; i++) if (minutes[i] == config.wallpaperRotationMinutes) rotationSelected = i;
        final int currentRotationChoice = rotationSelected;
        String rotationText = currentRotationChoice < 0
                ? String.valueOf(config.wallpaperRotationMinutes) : rotationLabels[currentRotationChoice];
        row("wallpaper_rotation_minutes", R.string.feature_rotation, rotationText,
                R.drawable.ic_settings_motion, 0xFF0A84FF,
                () -> choose(getString(R.string.feature_rotation), rotationLabels, currentRotationChoice, which -> {
                    int picked = minutes[which];
                    features.edit().putInt("wallpaper_rotation_minutes", picked).apply();
                    if (picked > 0 && PersonalWallpapers.isUserPhoto(config.wallpaperChoice)) {
                        if (PersonalWallpapers.listPhotos(this).size() >= 2) {
                            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, PersonalWallpapers.COLLECTION).apply();
                        } else {
                            Toast.makeText(this, R.string.personal_wallpaper_rotation_need_more, Toast.LENGTH_LONG).show();
                        }
                    }
                    markChanged(); render();
                }));
        String[] orders = {getString(R.string.feature_sequential), getString(R.string.feature_shuffle)};
        row("wallpaper_order", R.string.feature_order, orders[WallpaperRotation.ORDER_SEQUENTIAL.equals(config.wallpaperOrder) ? 0 : 1],
                R.drawable.ic_quick_apps, 0xFFBF5AF2, () -> choose(getString(R.string.feature_order), orders,
                        WallpaperRotation.ORDER_SEQUENTIAL.equals(config.wallpaperOrder) ? 0 : 1, which -> {
                            features.edit().putString("wallpaper_order", which == 0 ? WallpaperRotation.ORDER_SEQUENTIAL : WallpaperRotation.ORDER_SHUFFLE).apply();
                            markChanged(); render();
                        }));
        toggle("wallpaper_true_color", R.string.feature_true_color, config.wallpaperTrueColor, R.drawable.ic_quick_picture, 0xFFFF9F0A, R.string.feature_true_color_help);
        slider("dock_opacity", R.string.dock_opacity_label, R.drawable.ic_quick_picture, 0xFF0A84FF, 0xFF0A84FF,
                config.dockOpacity, 100, R.string.feature_slider_help, val -> {
                    choices.edit().remove(Config.DOCK_OPACITY_KEY).apply();
                    features.edit().putInt("dock_opacity", val).apply();
                });
        slider("wallpaper_dim", R.string.feature_dim, R.drawable.ic_quick_picture, 0xFFBF5AF2, 0xFFBF5AF2,
                config.wallpaperDim, 0, R.string.feature_slider_help,
                val -> features.edit().putInt("wallpaper_dim", val).apply());
    }

    private String wallpaperLabel(String value) {
        if (PersonalWallpapers.COAST_VIDEO.equals(value) || value.startsWith(PersonalWallpapers.PREFIX)) {
            return PersonalWallpapers.label(this, value);
        }
        if (FeaturePreferences.SCENES.equals(value)) return getString(R.string.feature_scenes);
        if (value.startsWith(FeaturePreferences.SCENE_PREFIX)) return SceneFiles.label(value.substring(FeaturePreferences.SCENE_PREFIX.length()));
        if (Config.WALLPAPER_AURORA.equals(value)) return getString(R.string.wallpaper_aurora);
        if (Config.WALLPAPER_OFF.equals(value)) return getString(R.string.off);
        if (Config.WALLPAPER_THEME.equals(value)) return getString(R.string.wallpaper_theme);
        return value;
    }

    private void chooseWallpaper() {
        List<String> values = new ArrayList<>(java.util.Arrays.asList(Config.WALLPAPER_THEME, Config.WALLPAPER_AURORA, Config.WALLPAPER_OFF, FeaturePreferences.SCENES));
        List<File> personalFiles = PersonalWallpapers.list(this);
        if (PersonalWallpapers.listPhotos(this).size() >= 2 || PersonalWallpapers.isCollection(config.wallpaperChoice)) {
            values.add(PersonalWallpapers.COLLECTION);
        }
        values.add(PersonalWallpapers.COAST_VIDEO);
        for (File file : SceneFiles.images(this)) values.add(FeaturePreferences.SCENE_PREFIX + file.getName());
        for (File file : personalFiles) values.add(PersonalWallpapers.PREFIX + file.getName());
        String[] labels = new String[values.size()]; for (int i = 0; i < labels.length; i++) labels[i] = wallpaperLabel(values.get(i));
        choose(getString(R.string.feature_source), labels, values.indexOf(config.wallpaperChoice), which -> {
            String value = values.get(which);
            if (Config.WALLPAPER_THEME.equals(value)) choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
            else choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, value).apply();
            if ((FeaturePreferences.SCENES.equals(value) || PersonalWallpapers.isCollection(value))
                    && config.wallpaperRotationMinutes == 0)
                features.edit().putInt("wallpaper_rotation_minutes", 60).apply();
            markChanged(); render();
        });
    }

    private void deletePersonalWallpaper() {
        List<File> files = PersonalWallpapers.list(this);
        if (files.isEmpty()) { render(); return; }
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            labels[i] = wallpaperLabel(PersonalWallpapers.PREFIX + files.get(i).getName());
        }
        choose(getString(R.string.personal_wallpaper_delete), labels, -1, which -> {
            String choice = PersonalWallpapers.PREFIX + files.get(which).getName();
            String label = labels[which];
            AlertDialog confirm = new AlertDialog.Builder(this)
                    .setTitle(R.string.personal_wallpaper_delete)
                    .setMessage(getString(R.string.personal_wallpaper_delete_confirm, label))
                    .setPositiveButton(R.string.personal_wallpaper_delete_action, (d, btn) -> {
                        PersonalWallpapers.delete(this, choice);
                        String active = choices.getString(Config.WALLPAPER_CHOICE_KEY, "");
                        if (choice.equals(active)
                                || (PersonalWallpapers.isCollection(active) && PersonalWallpapers.listPhotos(this).isEmpty())) {
                            choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                        }
                        Toast.makeText(this, R.string.personal_wallpaper_deleted, Toast.LENGTH_SHORT).show();
                        markChanged();
                        render();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            glass(confirm);
        });
    }

    private void updatePreview() {
        ((HomePreviewView) findViewById(R.id.featurePreview)).refresh(config, APPS.equals(page) ? editingPackage : null);
    }

    private void glow() {
        heading(R.string.feature_glow, R.string.feature_glow_help); section(R.string.feature_glow);
        String[] styleValues = {Config.GLOW_RADIAL, Config.GLOW_EDGE, Config.GLOW_HORIZON, Config.GLOW_OFF};
        String[] styleLabels = {
                getString(R.string.feature_glow_radial),
                getString(R.string.feature_glow_edge),
                getString(R.string.feature_glow_horizon),
                getString(R.string.feature_glow_off)
        };
        int selectedStyle = 0;
        for (int i = 0; i < styleValues.length; i++) if (styleValues[i].equals(config.glowStyle)) selectedStyle = i;
        final int currentStyleIndex = selectedStyle;
        SettingsRowView styleRow = row("glow_style", R.string.feature_glow_style, styleLabels[selectedStyle],
                R.drawable.ic_quick_picture, 0xFFBF5AF2,
                () -> choose(getString(R.string.feature_glow_style), styleLabels, currentStyleIndex, which -> {
                    String nextStyle = styleValues[which];
                    SharedPreferences.Editor edit = features.edit().putString("glow_style", nextStyle);
                    if (!Config.GLOW_OFF.equals(nextStyle) && config.glowIntensity <= 0) edit.putInt("glow_intensity", 100);
                    edit.apply(); markChanged(); render();
                }));

        int effectiveIntensity = Config.GLOW_OFF.equals(config.glowStyle) ? 0 : config.glowIntensity;
        slider("glow_intensity", R.string.feature_glow_intensity, R.drawable.ic_settings_motion, 0xFF0A84FF, 0xFF0A84FF,
                effectiveIntensity, 100, R.string.feature_glow_intensity_help, val -> {
                    SharedPreferences.Editor edit = features.edit().putInt("glow_intensity", val);
                    if (val > 0 && Config.GLOW_OFF.equals(config.glowStyle)) {
                        edit.putString("glow_style", Config.GLOW_RADIAL);
                        styleRow.setValue(getString(R.string.feature_glow_radial), true);
                    }
                    edit.apply();
                });

        slider("tile_glow_intensity", R.string.feature_glow_tile, R.drawable.ic_quick_picture, 0xFF64D2FF, 0xFF64D2FF,
                config.tileGlowIntensity, 100, R.string.feature_glow_tile_help, val ->
                        features.edit()
                                .putInt("tile_glow_intensity", val)
                                .putBoolean("tile_glow", val > 0)
                                .apply());
    }

    private void weather() {
        heading(R.string.feature_weather, R.string.feature_weather_help); section(R.string.feature_weather);
        toggle("weather_enabled", R.string.feature_weather_enabled,
                features.getBoolean("weather_enabled", true), R.drawable.ic_quick_picture, 0xFF64D2FF, 0);
        cityChoice();
        toggle("weather_city", R.string.weather_show_city, features.getBoolean("weather_city", true), R.drawable.ic_quick_picture, 0xFF64D2FF, 0);
        toggle("weather_details", R.string.weather_show_details, features.getBoolean("weather_details", true), R.drawable.ic_quick_picture, 0xFF64D2FF, 0);
        integerChoice("weather_size", R.string.weather_text_size, features.getInt("weather_size", WeatherFetcher.DEFAULT_TEXT_SIZE),
                new int[]{8, 10, 12, 14, 16, 20, 24}, new String[]{"8", "10", "12", "14", "16", "20", "24"}, R.drawable.ic_settings_motion);
    }

    private void cityChoice() {
        row("location", R.string.feature_city, features.getString("city", getString(R.string.feature_not_set)), R.drawable.ic_settings, 0xFF30D158,
                () -> textInput(getString(R.string.feature_city_prompt), "", getString(R.string.feature_city_hint), R.string.feature_search, value -> {
                    if (value.trim().length() < 2) throw new IllegalArgumentException(getString(R.string.feature_no_cities));
                    searchCity(value.trim());
                }));
    }

    private void searchCity(String query) {
        async(() -> FeatureNetwork.get("https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(query, "UTF-8") + "&count=5&language="
                + android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage()
                + "&format=json"), result -> {
            JSONArray found = result.optJSONArray("results");
            if (found == null || found.length() == 0) { message(R.string.feature_no_cities); return; }
            List<JSONObject> locations = new ArrayList<>(); List<String> labels = new ArrayList<>();
            for (int i = 0; i < Math.min(5, found.length()); i++) {
                JSONObject city = found.optJSONObject(i); if (city == null) continue;
                double lat = city.optDouble("latitude", Double.NaN), lon = city.optDouble("longitude", Double.NaN);
                if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
                String label = city.optString("name") + ", " + city.optString("admin1") + ", " + city.optString("country");
                locations.add(city); labels.add(Cards.cleanLabel(label, query));
            }
            if (labels.isEmpty()) { message(R.string.feature_no_cities); return; }
            choose(getString(R.string.feature_city), labels.toArray(new String[0]), -1, which -> {
                JSONObject city = locations.get(which);
                features.edit().putString("city", labels.get(which)).putString("latitude", String.valueOf(city.optDouble("latitude")))
                        .putString("longitude", String.valueOf(city.optDouble("longitude"))).apply(); markChanged(); render();
            });
        });
    }
    private void updateCalendar() { async(() -> RaceCalendarStore.refresh(getApplicationContext()), result -> { markChanged(); render(); }); }

    private void clock() {
        heading(R.string.feature_idle_clock, R.string.feature_idle_help); section(R.string.feature_idle_clock);
        int[] minutes = {0, 5, 10, 15, 30, 60};
        integerChoice("home_idle_clock_minutes", R.string.feature_clock_timer, config.homeIdleClockMinutes, minutes, minuteLabels(minutes), R.drawable.ic_settings_motion);
        toggle("clock_wallpaper", R.string.feature_clock_wallpaper, config.clockWallpaper, R.drawable.ic_quick_picture,0xFF0A84FF,0);
        toggle("clock_glow", R.string.feature_clock_glow, config.clockGlow,R.drawable.ic_quick_picture,0xFFBF5AF2,0);
        slider("clock_wallpaper_dim", R.string.feature_dim, R.drawable.ic_quick_picture, 0xFFBF5AF2, 0xFFBF5AF2,
                config.clockWallpaperDim, getResources().getInteger(R.integer.clock_wallpaper_dim),
                R.string.feature_slider_help,
                val -> features.edit().putInt("clock_wallpaper_dim", val).apply());
        if(config.openOnStartChoice) {
            String picked=choices.getString(OpenOnStart.KEY,"");
            row("open_on_start",R.string.open_on_start_label,picked.isEmpty()?getString(R.string.off):appName(picked),
                    R.drawable.ic_quick_apps,0xFF0A84FF,this::chooseStartupApp);
            integerChoice("open_on_start_network_wait",R.string.feature_startup_wait,config.openOnStartNetworkWait,
                    new int[]{0,30,60,120,300,600},new String[]{getString(R.string.off),"30 s","60 s","120 s","300 s","600 s"},R.drawable.ic_settings_motion);
        }
        row("clock_preview", R.string.feature_clock_preview, "", R.drawable.ic_quick_screensaver, 0xFF0A84FF,
                () -> startActivity(new Intent(this, ClockActivity.class)));
    }

    private void chooseStartupApp() {
        List<ResolveInfo> apps=catalog();
        String[] labels=new String[apps.size()+1];labels[0]=getString(R.string.off);
        for(int i=0;i<apps.size();i++)labels[i+1]=label(apps.get(i));
        choose(getString(R.string.open_on_start_label),labels,-1,which->{
            String pkg=which==0?"":apps.get(which-1).activityInfo.packageName;
            choices.edit().putString(OpenOnStart.KEY,pkg).apply();OpenOnStart.skipThisBoot(this);markChanged();render();
        });
    }

    private List<ResolveInfo> catalog() {
        Map<String, ResolveInfo> packages = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER})
            for (ResolveInfo app : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0))
                if (app.activityInfo != null && app.activityInfo.exported && !app.activityInfo.packageName.equals(getPackageName())
                        && !app.activityInfo.packageName.equals(getPackageName() + ".test")) packages.putIfAbsent(app.activityInfo.packageName, app);
        List<ResolveInfo> apps = new ArrayList<>(packages.values()); Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(label(a), label(b))); return apps;
    }
    private String label(ResolveInfo app) { return Cards.cleanLabel(String.valueOf(app.loadLabel(getPackageManager())), app.activityInfo.packageName); }
    private String appName(String pkg) {
        try { return Cards.cleanLabel(String.valueOf(getPackageManager().getApplicationInfo(pkg, 0).loadLabel(getPackageManager())), pkg); }
        catch (Exception ignored) { return pkg; }
    }
    private String appValue(String key, String pkg) { return FeaturePreferences.object(features.getString(key, "{}")).optString(pkg, ""); }
    private void apps() {
        heading(R.string.feature_apps, R.string.feature_apps_help);
        if (editingPackage == null) {
            section(R.string.feature_apps);
            List<ResolveInfo> apps = catalog();
            if (apps.isEmpty()) summary.setText(R.string.feature_no_apps);
            for (ResolveInfo app : apps) {
                String pkg = app.activityInfo.packageName;
                row("app:" + pkg, label(app), appValue("app_labels", pkg), R.drawable.ic_apps, 0xFF5E5CE6,
                        () -> { editingPackage = pkg; selectedKey = null; render(); });
            }
            return;
        }
        String pkg = editingPackage; title.setText(appName(pkg)); section(R.string.feature_apps);
        row("app_label", R.string.feature_app_label, appValue("app_labels", pkg), R.drawable.ic_apps, 0xFF5E5CE6,
                () -> textInput(getString(R.string.feature_app_label), appValue("app_labels", pkg), appName(pkg), value -> {
                    FeaturePreferences.putApp(this, "app_labels", pkg, value.isEmpty() ? null : Cards.cleanLabel(value, appName(pkg)));
                    markChanged(); render();
                }));
        row("app_action", R.string.feature_app_action, appValue("launch_actions", pkg).isEmpty() ? getString(R.string.feature_default) : getString(R.string.feature_custom),
                R.drawable.ic_quick_apps, 0xFF0A84FF, () -> textInput(getString(R.string.feature_app_action), appValue("launch_actions", pkg), getString(R.string.feature_app_action_help), value -> {
                    if (!value.isEmpty() && LaunchActions.inputId(value) == null) {
                        ResolveInfo target = getPackageManager().resolveActivity(LaunchActions.intent(pkg,value), 0);
                        if (target == null || target.activityInfo == null || !target.activityInfo.exported
                                || target.activityInfo.permission != null && checkSelfPermission(target.activityInfo.permission)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            throw new IllegalArgumentException(getString(R.string.feature_app_action_error));
                    }
                    FeaturePreferences.putApp(this, "launch_actions", pkg, value); markChanged(); render();
                }));
        String artwork = appValue("app_banners", pkg);
        String artworkLabel = artwork.isEmpty() ? getString(R.string.feature_default)
                : artwork.equals("icon") ? getString(R.string.feature_app_icon)
                : artwork.equals("banner_tv") ? getString(R.string.feature_tv_art)
                : AppArtwork.isAppIcon(artwork) ? getString(R.string.feature_icon_from, appName(artwork.substring(AppArtwork.APP_ICON_PREFIX.length())))
                : getString(R.string.feature_custom);
        row("app_banner", R.string.feature_app_banner, artworkLabel,
                R.drawable.ic_quick_picture, 0xFFFF9F0A, () -> choose(getString(R.string.feature_app_banner),
                        new String[]{getString(R.string.feature_default), getString(R.string.feature_app_icon), getString(R.string.feature_tv_art), getString(R.string.feature_other_app_icon), getString(R.string.feature_custom_image)},
                        artwork.isEmpty() ? 0 : artwork.equals("icon") ? 1 : artwork.equals("banner_tv") ? 2 : AppArtwork.isAppIcon(artwork) ? 3 : 4, which -> {
                            if (which == 3) { chooseIcon(pkg, artwork); return; }
                            if (which == 4) { chooseImage(pkg); return; }
                            FeaturePreferences.putApp(this, "app_banners", pkg, which == 0 ? "" : which == 1 ? "icon" : "banner_tv");
                            markChanged(); render();
                        }));
        row("app_reset", R.string.feature_reset_app, "", R.drawable.ic_settings, 0xFF737989, () -> {
            for (String key : new String[]{"app_labels", "launch_actions", "app_banners"}) FeaturePreferences.putApp(this, key, pkg, null);
            AppArtwork.file(this, pkg).delete(); markChanged(); render();
        });
    }

    private void chooseIcon(String target, String artwork) {
        List<ResolveInfo> apps = catalog();
        if (apps.isEmpty()) { message(R.string.feature_no_apps); return; }
        String[] labels = new String[apps.size()]; int checked = -1;
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = label(apps.get(i));
            if ((AppArtwork.APP_ICON_PREFIX + apps.get(i).activityInfo.packageName).equals(artwork)) checked = i;
        }
        choose(getString(R.string.feature_other_app_icon), labels, checked, which -> {
            FeaturePreferences.putApp(this, "app_banners", target, AppArtwork.APP_ICON_PREFIX + apps.get(which).activityInfo.packageName);
            markChanged(); render();
        }, apps);
    }

    private void chooseImage(String pkg) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        if (getPackageManager().resolveActivity(pick, 0) == null) { message(R.string.feature_no_image_picker); return; }
        imagePackage = pkg;
        try { startActivityForResult(pick, 20); }
        catch (ActivityNotFoundException e) { message(R.string.feature_no_image_picker); }
    }
    private void choosePersonalWallpaper(boolean video) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(video ? "video/*" : "image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (!video) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(pick, video ? 22 : 21); }
        catch (ActivityNotFoundException unavailable) { message(R.string.feature_no_image_picker); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == 21 || requestCode == 22) && resultCode == RESULT_OK && data != null) {
            boolean isVideo = requestCode == 22;
            List<android.net.Uri> uris = new ArrayList<>();
            if (!isVideo && data.getClipData() != null) {
                android.content.ClipData clip = data.getClipData();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    android.net.Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            int flags = data.getFlags();
            List<Boolean> persistedFlags = new ArrayList<>(uris.size());
            for (android.net.Uri uri : uris) {
                persistedFlags.add(PersonalWallpapers.tryPersistReadPermission(this, uri, flags));
            }
            async(() -> {
                List<String> imported = new ArrayList<>();
                Exception lastError = null;
                for (int i = 0; i < uris.size(); i++) {
                    try {
                        imported.add(PersonalWallpapers.importFile(
                                getApplicationContext(), uris.get(i), isVideo, persistedFlags.get(i)));
                    } catch (Exception e) {
                        lastError = e;
                    }
                }
                if (imported.isEmpty()) {
                    throw lastError != null ? lastError : new java.io.IOException("Import failed");
                }
                return imported;
            }, imported -> {
                String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, "");
                boolean rotating = features.getInt("wallpaper_rotation_minutes", 0) > 0;
                if (!isVideo && (imported.size() > 1
                        || ((PersonalWallpapers.isCollection(current) || rotating)
                        && PersonalWallpapers.listPhotos(this).size() >= 2))) {
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, PersonalWallpapers.COLLECTION).apply();
                    if (imported.size() > 1 && !rotating) {
                        features.edit().putInt("wallpaper_rotation_minutes", 60).apply();
                    }
                } else {
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, imported.get(imported.size() - 1)).apply();
                }
                markChanged(); render();
            }, R.string.personal_wallpaper_import_failed);
            return;
        }
        if (requestCode == 20 && resultCode == RESULT_OK && imagePackage != null && data != null && data.getData() != null) {
            String pkg = imagePackage; android.net.Uri uri = data.getData();
            async(() -> { AppArtwork.importImage(getApplicationContext(), pkg, uri); return pkg; }, value -> {
                FeaturePreferences.putApp(this, "app_banners", value, "custom"); markChanged(); render();
            }, R.string.feature_image_error);
        }
    }

    private void openOrEnableAccessibility() {
        if (!remoteAccessibilityEnabled())
            Toast.makeText(this, R.string.remote_helper_disabled_warning, Toast.LENGTH_LONG).show();
        openSystemAccessibilitySettings();
    }

    private boolean remoteAccessibilityEnabled() {
        return accessibilityEnabled(InputKeyService.class);
    }

    private boolean accessibilityEnabled(Class<?> service) {
        android.view.accessibility.AccessibilityManager manager =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        ComponentName expected = new ComponentName(this, service);
        for (android.accessibilityservice.AccessibilityServiceInfo info :
                manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ResolveInfo resolved = info.getResolveInfo();
            if (resolved != null && resolved.serviceInfo != null
                    && expected.equals(new ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name))) return true;
        }
        return false;
    }

    private void openSystemAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }

        String[] tvComponents = {
                "com.android.tv.settings/.accessibility.AccessibilityActivity",
                "com.google.android.tv.settings/.accessibility.AccessibilityActivity",
                "com.android.tv.settings/.system.AccessibilityActivity"
        };
        for (String cmp : tvComponents) {
            try {
                ComponentName cn = ComponentName.unflattenFromString(cmp);
                if (cn != null) {
                    Intent tvIntent = new Intent().setComponent(cn);
                    startActivity(tvIntent);
                    return;
                }
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }

        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }

        message(R.string.not_available);
    }

    private void remote() {
        heading(R.string.feature_remote, R.string.feature_remote_help); section(R.string.feature_remote);
        boolean enabled = remoteAccessibilityEnabled();
        int status = InputKeyService.connected ? R.string.on : enabled ? R.string.remote_helper_not_running : R.string.off;
        row("remote_access", R.string.feature_remote_access, getString(status), R.drawable.ic_settings, 0xFF737989,
                this::openOrEnableAccessibility);
        int panelKey = features.getInt(RemotePanelKeys.PANEL_KEY, 0);
        int selectedPanel = 0;
        for (int i = 1; i < RemotePanelKeys.PANEL_CHOICES.length; i++)
            if (panelKey == RemotePanelKeys.PANEL_CHOICES[i]) selectedPanel = i;
        final int panelChoice = selectedPanel;
        int[] panelLabels = {R.string.remote_panel_off, R.string.remote_panel_menu,
                R.string.remote_panel_red, R.string.remote_panel_settings};
        row("remote_panel", R.string.remote_panel_key, getString(panelLabels[panelChoice]),
                R.drawable.ic_quick_apps, 0xFF0A84FF,
                () -> choose(getString(R.string.remote_panel_key),
                        new String[]{getString(panelLabels[0]), getString(panelLabels[1]),
                                getString(panelLabels[2]), getString(panelLabels[3])}, panelChoice,
                        which -> {
                            features.edit().putInt(RemotePanelKeys.PANEL_KEY,
                                    RemotePanelKeys.PANEL_CHOICES[which]).apply();
                            markChanged(); render();
                        }));
        remoteToggle("remote_long_home", R.string.remote_long_home, RemotePanelKeys.LONG_HOME);
        remoteToggle("remote_play_pause", R.string.remote_play_pause, RemotePanelKeys.PLAY_PAUSE);
        row("remote_learn", R.string.feature_remote_learn, "", R.drawable.ic_quick_apps, 0xFF0A84FF, this::learn);
        row("remote_home", R.string.remote_home_fix, getString(RemoteButtonMapper.homeFixEnabled(this) ? R.string.on : R.string.off),
                R.drawable.ic_home_settings, 0xFF30B85A, () -> {
                    boolean next = !RemoteButtonMapper.homeFixEnabled(this);
                    features.edit().putBoolean(RemoteButtonMapper.HOME_FIX, next).apply();
                    render();
                    if (next && !InputKeyService.connected)
                        message(R.string.remote_helper_required);
                });
        for (String button : RemoteButtonMapper.buttons(this)) {
            String name = button.startsWith("scan:") ? getString(R.string.feature_remote_button, button.substring(5))
                    : KeyEvent.keyCodeToString(Integer.parseInt(button)).replace("KEYCODE_", "").replace('_', ' ');
            StringBuilder summary = new StringBuilder();
            for (int taps = 1; taps <= 3; taps++) {
                String target = RemoteButtonMapper.target(this, button, taps);
                if (!target.isEmpty()) {
                    if (summary.length() > 0) summary.append(" · ");
                    summary.append(taps).append("×: ").append(remoteTargetName(target));
                }
            }
            row("remote:" + button, name, summary.toString(), R.drawable.ic_apps, 0xFF5E5CE6,
                    () -> editButton(button));
        }
    }
    private void remoteToggle(String id, int label, String preference) {
        boolean enabled = features.getBoolean(preference, false);
        row(id, label, getString(enabled ? R.string.on : R.string.off), R.drawable.ic_quick_apps,
                0xFF0A84FF, () -> {
                    features.edit().putBoolean(preference, !enabled).apply();
                    markChanged(); render();
                });
    }

    private void learn() {
        int session = request;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.feature_remote_learn).setMessage(R.string.feature_remote_press)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.feature_remote_access, (d, which) -> {
                    RemoteLearning.stop();
                    openOrEnableAccessibility();
                }).create();
        dialog.setOnDismissListener(d -> RemoteLearning.stop()); dialog.show(); glass(dialog);
        dialog.setCanceledOnTouchOutside(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { RemoteLearning.stop(); dialog.dismiss(); });
        RemoteLearning.start(button -> {
            if (isFinishing() || isDestroyed() || session != request) return;
            dialog.dismiss(); editButton(button);
        });
        dialog.setOnKeyListener((d, code, event) -> {
            if (code == KeyEvent.KEYCODE_BACK) RemoteLearning.stop();
            return RemoteLearning.handle(event);
        });
    }
    private String remoteTargetName(String target) {
        if (RemoteButtonMapper.HOME.equals(target)) return getString(R.string.settings_preview_home);
        if (RemoteButtonMapper.CONTROL.equals(target)) return getString(R.string.quick_settings);
        if (RemoteButtonMapper.INPUTS.equals(target)) return getString(R.string.inputs);
        if ("screensaver".equals(target)) return getString(R.string.quick_screensaver);
        return appName(target);
    }

    private void editButton(String button) {
        choose(getString(R.string.feature_remote_button, button),
                new String[]{getString(R.string.remote_single), getString(R.string.remote_double), getString(R.string.remote_triple)},
                -1, which -> chooseRemoteTarget(button, which + 1));
    }

    private void chooseRemoteTarget(String button, int taps) {
        String[] targets = {RemoteButtonMapper.HOME, RemoteButtonMapper.CONTROL, RemoteButtonMapper.INPUTS, "screensaver"};
        String[] labels = {getString(R.string.feature_remote_choose), getString(R.string.settings_preview_home),
                getString(R.string.quick_settings), getString(R.string.inputs), getString(R.string.quick_screensaver), getString(R.string.feature_unassign)};
        choose(getString(R.string.feature_remote_button, button) + " · " + taps + "×", labels, -1, which -> {
            if (which == 0) chooseApp(pkg -> saveButton(button, taps, pkg), false);
            else saveButton(button, taps, which == 5 ? null : targets[which - 1]);
        });
    }

    private void saveButton(String button, int taps, String target) {
        RemoteButtonMapper.save(this, button, taps, target); markChanged(); render();
    }
    private void chooseApp(Consumer<String> selected, boolean removal) {
        List<ResolveInfo> apps = catalog(); List<String> labels = new ArrayList<>();
        if (removal) labels.add(getString(R.string.feature_unassign));
        for (ResolveInfo app : apps) labels.add(label(app));
        if (labels.isEmpty()) { message(R.string.feature_no_apps); return; }
        choose(getString(R.string.feature_remote_choose), labels.toArray(new String[0]), -1,
                which -> selected.accept(removal && which == 0 ? null : apps.get(which - (removal ? 1 : 0)).activityInfo.packageName));
    }

    private void choose(String heading, String[] labels, int checked, Consumer<Integer> selected) {
        choose(heading, labels, checked, selected, null);
    }
    private void choose(String heading, String[] labels, int checked, Consumer<Integer> selected, List<ResolveInfo> icons) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent); row.setTextColor(0xFFFFFFFF); row.setTextSize(16);
                if (icons != null) {
                    android.graphics.drawable.Drawable icon = icons.get(position).loadIcon(getPackageManager());
                    icon.setBounds(0, 0, dp(32), dp(32));
                    row.setCompoundDrawablesRelative(icon, null, null, null); row.setCompoundDrawablePadding(dp(16));
                }
                row.setSoundEffectsEnabled(sounds); return row;
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(heading).setSingleChoiceItems(adapter, checked,
                (d, which) -> { d.dismiss(); selected.accept(which); }).setNegativeButton(android.R.string.cancel, null).show();
        glass(dialog);
    }
    private void textInput(String heading, String initial, String hint, Consumer<String> saved) {
        textInput(heading, initial, hint, R.string.feature_save, saved);
    }
    private void textInput(String heading, String initial, String hint, int positive, Consumer<String> saved) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextColor(0xFFFFFFFF); input.setHintTextColor(0x80FFFFFF);
        input.setText(initial); input.setHint(hint); input.setPadding(dp(20), dp(16), dp(20), dp(16));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)}); input.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(heading).setView(input)
                .setPositiveButton(positive, null).setNegativeButton(android.R.string.cancel, null).show();
        glass(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { saved.accept(input.getText().toString().trim()); dialog.dismiss(); }
            catch (IllegalArgumentException error) { input.setError(error.getMessage()); }
        });
    }
    private void glass(AlertDialog dialog) {
        if (activeDialog != null && activeDialog != dialog && activeDialog.isShowing()) activeDialog.dismiss();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> { RemoteLearning.stop(); if (activeDialog == dialog) activeDialog = null; });
        GradientDrawable panel = new GradientDrawable(); panel.setColor(0xF2343840); panel.setCornerRadius(dp(28));
        if (dialog.getWindow() != null) { dialog.getWindow().setBackgroundDrawable(panel); dialog.getWindow().setDimAmount(.32f); }
        View message = dialog.findViewById(android.R.id.message); if (message instanceof TextView) ((TextView) message).setTextColor(0xD9FFFFFF);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which); if (button == null) continue;
            button.setAllCaps(false); button.setTextSize(16); button.setTextColor(getResources().getColorStateList(R.color.glass_action_text, getTheme()));
            button.setBackgroundResource(R.drawable.glass_menu_row); button.setPadding(dp(18), dp(10), dp(18), dp(10)); button.setSoundEffectsEnabled(sounds);
        }
        NavSound.attachDialog(dialog, () -> sounds);
    }
    private <T> void async(Callable<T> work, Consumer<T> success) {
        async(work, success, R.string.feature_network_error);
    }
    private <T> void async(Callable<T> work, Consumer<T> success, int errorMessage) {
        if (busy) return;
        busy = true;
        int token = ++request; summary.setText(R.string.feature_loading);
        FeatureNetwork.WORKER.execute(() -> {
            try {
                T result = work.call(); runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && token == request) { busy = false; summary.setText(pageHelp); success.accept(result); }
                });
            } catch (Exception | OutOfMemoryError error) {
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && token == request) { busy = false; summary.setText(pageHelp); message(errorMessage); } });
            }
        });
    }
    private void message(int text) { AlertDialog dialog = new AlertDialog.Builder(this).setMessage(text).setPositiveButton(android.R.string.ok, null).show(); glass(dialog); }
    private String[] minuteLabels(int[] values) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i] == 0 ? getString(R.string.off) : getString(R.string.feature_minutes, values[i]);
        return labels;
    }
    private static View firstFocusable(View view) {
        if (view.isFocusable()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View child = firstFocusable(((ViewGroup) view).getChildAt(i)); if (child != null) return child;
        }
        return null;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
