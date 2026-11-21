package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Apple TV glass settings screen for Android's built-in system Equalizer, grouped into Low / Mid / High. */
public final class EqualizerActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private EqualizerCurveView curveView;
    private TextView statusView;
    private TextView detailTitle;
    private TextView detailHint;
    private SettingsRowView toggleRow;
    private SettingsRowView presetRow;
    private LinearLayout zonesContainer;
    private SettingsRowView resetRow;
    private View systemDivider;
    private SettingsRowView systemPanelRow;
    private EqualizerBandView[] bandViews = new EqualizerBandView[0];
    private SystemEqualizer.Snapshot snapshot;
    private Intent systemPanelIntent;
    private boolean navigationSounds;
    private boolean confirmPressed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);
        Config config = Config.load(this);
        navigationSounds = config.navigationSounds;
        if (navigationSounds) NavSound.prepare();

        curveView = findViewById(R.id.equalizerCurve);
        statusView = findViewById(R.id.equalizerStatus);
        detailTitle = findViewById(R.id.equalizerDetailTitle);
        detailHint = findViewById(R.id.equalizerDetailHint);
        toggleRow = findViewById(R.id.equalizerToggle);
        presetRow = findViewById(R.id.equalizerPreset);
        zonesContainer = findViewById(R.id.equalizerZonesContainer);
        resetRow = findViewById(R.id.equalizerReset);
        systemDivider = findViewById(R.id.equalizerSystemDivider);
        systemPanelRow = findViewById(R.id.equalizerSystemPanel);

        toggleRow.setIcon(R.drawable.ic_settings_equalizer, 0xFF0A84FF);
        presetRow.setIcon(R.drawable.ic_quick_sound, 0xFFBF5AF2);
        resetRow.setIcon(R.drawable.ic_settings_update, 0xFFFF9F0A);
        resetRow.setValue("", false);
        systemPanelRow.setIcon(R.drawable.ic_settings, 0xFF64D2FF);
        systemPanelRow.setValue("", true);

        toggleRow.setOnClickListener(v -> {
            if (snapshot == null || !snapshot.available) {
                Toast.makeText(this, R.string.equalizer_unavailable, Toast.LENGTH_LONG).show();
                return;
            }
            snapshot = SystemEqualizer.setEnabled(this, !snapshot.enabled);
            setResult(RESULT_OK);
            render();
        });
        presetRow.setOnClickListener(v -> choosePreset());
        resetRow.setOnClickListener(v -> {
            if (snapshot == null || !snapshot.available) return;
            snapshot = SystemEqualizer.resetFlat(this);
            setResult(RESULT_OK);
            render();
            Toast.makeText(this, R.string.equalizer_reset_done, Toast.LENGTH_SHORT).show();
        });

        systemPanelIntent = SystemEqualizer.systemPanelIntent(this);
        if (systemPanelIntent != null) {
            systemDivider.setVisibility(View.VISIBLE);
            systemPanelRow.setVisibility(View.VISIBLE);
            systemPanelRow.setOnClickListener(v -> {
                try {
                    startActivity(systemPanelIntent);
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            });
        }

        attachFocusDetail(toggleRow, getString(R.string.equalizer_enable), getString(R.string.equalizer_enable_hint), -1);
        attachFocusDetail(presetRow, getString(R.string.equalizer_preset), getString(R.string.equalizer_preset_hint), -1);
        attachFocusDetail(resetRow, getString(R.string.equalizer_reset), getString(R.string.equalizer_reset_hint), -1);
        attachFocusDetail(systemPanelRow, getString(R.string.equalizer_system_panel), getString(R.string.equalizer_system_panel_hint), -1);

        snapshot = SystemEqualizer.openForSettings(this);
        buildZoneSections();
        render();
        toggleRow.requestFocus();
    }

    @Override
    protected void onStop() {
        SystemEqualizer.releaseIfDisabled(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        SystemEqualizer.releaseIfDisabled(this);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (NavSound.isConfirmKey(code)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) confirmPressed = true;
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean pressed = confirmPressed;
                confirmPressed = false;
                if (!pressed || event.isCanceled()) return true;
            }
        }
        return NavSound.dispatchActivityKey(this, event, navigationSounds, () -> super.dispatchKeyEvent(event));
    }

    private void buildZoneSections() {
        zonesContainer.removeAllViews();
        if (snapshot == null || !snapshot.available || snapshot.centerFreqsHz.length == 0) {
            bandViews = new EqualizerBandView[0];
            zonesContainer.setVisibility(View.GONE);
            return;
        }
        zonesContainer.setVisibility(View.VISIBLE);
        int count = snapshot.centerFreqsHz.length;
        bandViews = new EqualizerBandView[count];
        String hzUnit = getString(R.string.equalizer_unit_hz);
        String khzUnit = getString(R.string.equalizer_unit_khz);

        for (int zone = SystemEqualizer.ZONE_LOW; zone <= SystemEqualizer.ZONE_HIGH; zone++) {
            List<Integer> zoneBands = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (SystemEqualizer.bandZone(snapshot.centerFreqsHz[i]) == zone) zoneBands.add(i);
            }
            if (zoneBands.isEmpty()) continue;

            TextView header = new TextView(this);
            header.setText(SystemEqualizer.zoneTitleRes(zone));
            header.setTextColor(0x80FFFFFF);
            header.setTextSize(11);
            header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            header.setLetterSpacing(0.06f);
            header.setAllCaps(true);
            LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headerLp.topMargin = dp(22);
            header.setPadding(dp(18), 0, 0, 0);
            zonesContainer.addView(header, headerLp);

            LinearLayout group = new LinearLayout(this);
            group.setOrientation(LinearLayout.VERTICAL);
            group.setBackgroundResource(R.drawable.settings_group_bg);
            int pad = dp(4);
            group.setPadding(pad, pad, pad, pad);
            group.setClipChildren(false);
            group.setClipToPadding(false);
            LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            groupLp.topMargin = dp(8);
            zonesContainer.addView(group, groupLp);

            String zoneTitle = getString(SystemEqualizer.zoneTitleRes(zone));
            for (int j = 0; j < zoneBands.size(); j++) {
                int band = zoneBands.get(j);
                int freq = snapshot.centerFreqsHz[band];
                if (j > 0) group.addView(divider());
                EqualizerBandView row = new EqualizerBandView(this);
                row.setId(View.generateViewId());
                row.setSoundEffectsEnabled(navigationSounds);
                String freqLabel = SystemEqualizer.formatFrequency(freq, hzUnit, khzUnit);
                String title = zoneTitle + " · " + freqLabel;
                String hint = getString(SystemEqualizer.bandHintRes(freq));
                attachFocusDetail(row, title, hint, band);
                group.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                bandViews[band] = row;
            }
        }
    }

    private void render() {
        boolean available = snapshot != null && snapshot.available;
        boolean enabled = available && snapshot.enabled;
        toggleRow.setEnabled(available);
        toggleRow.setAlpha(available ? 1f : 0.5f);
        toggleRow.setToggle(enabled);

        String presetText = !available ? getString(R.string.not_available) : currentPresetLabel();
        presetRow.setValue(presetText, available && snapshot.presetNames.length > 0);
        presetRow.setEnabled(enabled && snapshot.presetNames.length > 0);
        presetRow.setAlpha(enabled && snapshot.presetNames.length > 0 ? 1f : 0.5f);

        String hz = getString(R.string.equalizer_unit_hz);
        String khz = getString(R.string.equalizer_unit_khz);
        String db = getString(R.string.equalizer_unit_db);
        for (int i = 0; i < bandViews.length && snapshot != null && i < snapshot.bandLevelsMb.length; i++) {
            EqualizerBandView row = bandViews[i];
            if (row == null) continue;
            final int band = i;
            int freq = snapshot.centerFreqsHz[i];
            int zone = SystemEqualizer.bandZone(freq);
            row.setEnabled(enabled);
            row.setAlpha(enabled ? 1f : 0.5f);
            String roleLabel = getString(SystemEqualizer.bandRoleRes(freq));
            String freqLabel = SystemEqualizer.formatFrequency(freq, hz, khz);
            String valueLabel = SystemEqualizer.formatDb(snapshot.bandLevelsMb[i], db);
            row.bind(roleLabel, freqLabel, valueLabel, snapshot.minLevelMb, snapshot.maxLevelMb,
                    snapshot.bandLevelsMb[i], true, SystemEqualizer.zoneAccentColor(zone),
                    new EqualizerBandView.Listener() {
                        @Override public void onStep(int direction) {
                            snapshot = SystemEqualizer.adjustBand(EqualizerActivity.this, band, direction * SystemEqualizer.STEP_MB);
                            setResult(RESULT_OK);
                            render();
                        }
                        @Override public void onReset() {
                            snapshot = SystemEqualizer.setBandLevel(EqualizerActivity.this, band, 0);
                            setResult(RESULT_OK);
                            render();
                        }
                    });
        }

        resetRow.setEnabled(enabled);
        resetRow.setAlpha(enabled ? 1f : 0.5f);

        curveView.update(snapshot, focusedBandIndex());
        if (!available) {
            statusView.setText(R.string.equalizer_unavailable);
        } else if (!enabled) {
            statusView.setText(getString(R.string.equalizer_status_off));
        } else {
            statusView.setText(getString(R.string.equalizer_status_on, presetText));
        }
        setSounds(getWindow().getDecorView());
    }

    private int focusedBandIndex() {
        for (int i = 0; i < bandViews.length; i++) {
            if (bandViews[i] != null && bandViews[i].isFocused()) return i;
        }
        return -1;
    }

    private String currentPresetLabel() {
        if (snapshot == null) return getString(R.string.settings_custom);
        if (snapshot.presetIndex >= 0 && snapshot.presetIndex < snapshot.presetNames.length) {
            return SystemEqualizer.presetLabel(this, snapshot.presetNames[snapshot.presetIndex]);
        }
        return getString(R.string.settings_custom);
    }

    private void choosePreset() {
        if (snapshot == null || !snapshot.available || snapshot.presetNames.length == 0) return;
        int count = snapshot.presetNames.length;
        String[] labels = new String[count + 1];
        for (int i = 0; i < count; i++) {
            labels[i] = SystemEqualizer.presetLabel(this, snapshot.presetNames[i]);
        }
        labels[count] = getString(R.string.settings_custom);
        int selected = snapshot.presetIndex >= 0 && snapshot.presetIndex < count ? snapshot.presetIndex : count;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.equalizer_preset)
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    int target = which < count ? which : SystemEqualizer.PRESET_CUSTOM;
                    snapshot = SystemEqualizer.selectPreset(this, target);
                    setResult(RESULT_OK);
                    d.dismiss();
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        glass(dialog);
    }

    private void attachFocusDetail(View view, String titleText, String hintText, int bandIndex) {
        view.setOnFocusChangeListener((v, focused) -> {
            v.animate().translationZ(focused ? 6 * getResources().getDisplayMetrics().density : 0)
                    .setDuration(130).start();
            if (focused) {
                detailTitle.setText(titleText);
                detailHint.setText(hintText);
                curveView.setFocusedBand(bandIndex);
            }
        });
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.5f * getResources().getDisplayMetrics().density)));
        lp.setMarginStart(dp(22));
        lp.setMarginEnd(dp(16));
        line.setLayoutParams(lp);
        return line;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void glass(AlertDialog dialog) {
        float density = getResources().getDisplayMetrics().density;
        if (dialog.getWindow() != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(0xF2343840);
            panel.setCornerRadius(28 * density);
            dialog.getWindow().setBackgroundDrawable(panel);
            dialog.getWindow().setDimAmount(.32f);
        }
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE}) {
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
        if (dialog.getWindow() != null) setSounds(dialog.getWindow().getDecorView());
        NavSound.attachDialog(dialog, () -> navigationSounds);
    }

    private void setSounds(View view) {
        view.setSoundEffectsEnabled(navigationSounds);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setSounds(group.getChildAt(i));
        }
    }
}
