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

/** Apple TV glass settings screen for Android's built-in system Equalizer and BassBoost. */
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
    private TextView bandsHeader;
    private LinearLayout bandsGroup;
    private LinearLayout bassContainer;
    private View bassDivider;
    private SettingsRowView resetRow;
    private View systemDivider;
    private SettingsRowView systemPanelRow;
    private final List<EqualizerBandView> bandViews = new ArrayList<>();
    private EqualizerBandView bassView;
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
        bandsHeader = findViewById(R.id.equalizerBandsHeader);
        bandsGroup = findViewById(R.id.equalizerBandsGroup);
        bassContainer = findViewById(R.id.equalizerBassContainer);
        bassDivider = findViewById(R.id.equalizerBassDivider);
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

        attachFocusDetail(toggleRow, R.string.equalizer_enable, R.string.equalizer_enable_hint, -1);
        attachFocusDetail(presetRow, R.string.equalizer_preset, R.string.equalizer_preset_hint, -1);
        attachFocusDetail(resetRow, R.string.equalizer_reset, R.string.equalizer_reset_hint, -1);
        attachFocusDetail(systemPanelRow, R.string.equalizer_system_panel, R.string.equalizer_system_panel_hint, -1);

        snapshot = SystemEqualizer.openForSettings(this);
        buildBandViews();
        buildBassView();
        render();
        toggleRow.requestFocus();
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

    private void buildBandViews() {
        bandsGroup.removeAllViews();
        bandViews.clear();
        if (snapshot == null || !snapshot.available || snapshot.centerFreqsHz.length == 0) {
            bandsHeader.setVisibility(View.GONE);
            bandsGroup.setVisibility(View.GONE);
            return;
        }
        bandsHeader.setVisibility(View.VISIBLE);
        bandsGroup.setVisibility(View.VISIBLE);
        for (int i = 0; i < snapshot.centerFreqsHz.length; i++) {
            final int band = i;
            if (i > 0) bandsGroup.addView(divider());
            EqualizerBandView row = new EqualizerBandView(this);
            row.setId(View.generateViewId());
            row.setSoundEffectsEnabled(navigationSounds);
            attachFocusDetail(row, R.string.equalizer_section_bands, R.string.equalizer_band_hint, band);
            bandsGroup.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bandViews.add(row);
        }
    }

    private void buildBassView() {
        bassContainer.removeAllViews();
        bassView = null;
        if (snapshot == null || !snapshot.available || !snapshot.bassBoostSupported) {
            bassContainer.setVisibility(View.GONE);
            bassDivider.setVisibility(View.GONE);
            return;
        }
        bassContainer.setVisibility(View.VISIBLE);
        bassDivider.setVisibility(View.VISIBLE);
        bassView = new EqualizerBandView(this);
        bassView.setId(View.generateViewId());
        bassView.setSoundEffectsEnabled(navigationSounds);
        attachFocusDetail(bassView, R.string.equalizer_bass_boost, R.string.equalizer_bass_hint, -1);
        bassContainer.addView(bassView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        for (int i = 0; i < bandViews.size() && snapshot != null && i < snapshot.bandLevelsMb.length; i++) {
            final int band = i;
            EqualizerBandView row = bandViews.get(i);
            row.setEnabled(enabled);
            row.setAlpha(enabled ? 1f : 0.5f);
            String freqLabel = SystemEqualizer.formatFrequency(snapshot.centerFreqsHz[i], hz, khz);
            String valueLabel = SystemEqualizer.formatDb(snapshot.bandLevelsMb[i], db);
            row.bind(freqLabel, valueLabel, snapshot.minLevelMb, snapshot.maxLevelMb,
                    snapshot.bandLevelsMb[i], true, 0xFF0A84FF, new EqualizerBandView.Listener() {
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

        if (bassView != null && snapshot != null) {
            bassView.setEnabled(enabled);
            bassView.setAlpha(enabled ? 1f : 0.5f);
            bassView.bind(getString(R.string.equalizer_bass_boost), snapshot.bassBoostPercent + "%",
                    0, 100, snapshot.bassBoostPercent, false, 0xFF30D158, new EqualizerBandView.Listener() {
                        @Override public void onStep(int direction) {
                            int next = snapshot.bassBoostPercent + direction * SystemEqualizer.BASS_STEP_PERCENT;
                            snapshot = SystemEqualizer.setBassBoost(EqualizerActivity.this, next);
                            setResult(RESULT_OK);
                            render();
                        }
                        @Override public void onReset() {
                            snapshot = SystemEqualizer.setBassBoost(EqualizerActivity.this, 0);
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
        } else if (snapshot.bassBoostSupported && snapshot.bassBoostPercent > 0) {
            statusView.setText(getString(R.string.equalizer_status_on_bass, presetText, snapshot.bassBoostPercent));
        } else {
            statusView.setText(getString(R.string.equalizer_status_on, presetText));
        }
        setSounds(getWindow().getDecorView());
    }

    private int focusedBandIndex() {
        for (int i = 0; i < bandViews.size(); i++) {
            if (bandViews.get(i).isFocused()) return i;
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

    private void attachFocusDetail(View view, int titleRes, int hintRes, int bandIndex) {
        view.setOnFocusChangeListener((v, focused) -> {
            v.animate().translationZ(focused ? 6 * getResources().getDisplayMetrics().density : 0)
                    .setDuration(130).start();
            if (focused) {
                detailTitle.setText(titleRes);
                detailHint.setText(hintRes);
                curveView.setFocusedBand(bandIndex);
            }
        });
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(0x14FFFFFF);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.5f * d)));
        lp.setMarginStart(Math.round(22 * d));
        lp.setMarginEnd(Math.round(16 * d));
        line.setLayoutParams(lp);
        return line;
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
