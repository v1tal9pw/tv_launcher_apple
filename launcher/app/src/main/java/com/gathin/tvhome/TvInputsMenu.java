package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared TV input listing and switching logic for both Home and the in-app control overlay. */
final class TvInputsMenu {
    private TvInputsMenu() { }

    static Intent liveTvIntent(Context context, Config cfg) {
        if (cfg.liveTvPackage.isEmpty()) return null;
        Intent intent = context.getPackageManager().getLeanbackLaunchIntentForPackage(cfg.liveTvPackage);
        if (intent != null || cfg.liveTvAction.isEmpty()) return intent;
        Intent byAction = new Intent(cfg.liveTvAction).setPackage(cfg.liveTvPackage);
        return context.getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    static boolean prefersDirectSourcePanel(Context context, Config cfg) {
        List<CoocaaSources.Source> coocaa = cfg.coocaaSources ? CoocaaSources.list(context) : Collections.emptyList();
        return coocaa.isEmpty() && !cfg.sourcePanelService.isEmpty() && liveTvIntent(context, cfg) == null;
    }

    static void openSourcePanel(Context context, Config cfg) {
        Intent intent = new Intent(cfg.sourcePanelAction);
        ComponentName component = ComponentName.unflattenFromString(cfg.sourcePanelService);
        if (component != null) intent.setComponent(component);
        int eq = cfg.sourcePanelExtra.indexOf('=');
        if (eq > 0) {
            try {
                intent.putExtra(cfg.sourcePanelExtra.substring(0, eq).trim(),
                        Integer.parseInt(cfg.sourcePanelExtra.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) { }
        }
        try {
            if (context.getPackageManager().resolveActivity(intent, 0) != null) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            context.startService(intent);
        } catch (Exception unavailable) {
            Toast.makeText(context, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    static void openInput(Context context, Config cfg, String inputId) {
        String sourceId = inputId;
        TvInputManager tim = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                TvInputInfo info = tim.getTvInputInfo(inputId);
                if (info != null && info.getParentId() != null) sourceId = info.getParentId();
            } catch (RuntimeException ignored) { }
        }
        Integer source = InputSources.mapped(cfg.inputSources, sourceId);
        if (source != null && source >= 0 && !cfg.inputAction.isEmpty()) {
            try {
                context.startActivity(new Intent(cfg.inputAction).putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (cfg.inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(cfg.inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!cfg.liveTvPackage.isEmpty()) {
            try {
                context.startActivity(new Intent(view).setPackage(cfg.liveTvPackage));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        try {
            context.startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            if (cfg.inputAction.isEmpty()) {
                Toast.makeText(context, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        openActivity(context, new Intent(cfg.inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    static boolean populate(Context context, LayoutInflater inflater, LinearLayout rows, Config cfg,
                            float density, boolean navigationSounds, Runnable dismissBeforeAction) {
        List<CoocaaSources.Source> coocaa = cfg.coocaaSources ? CoocaaSources.list(context) : Collections.emptyList();
        TvInputManager tim = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        boolean connectedOnly = cfg.inputsConnectedOnly;
        List<View> folded = new ArrayList<>();
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(context)).compareToIgnoreCase(String.valueOf(b.loadLabel(context)));
            });
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(context)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (cfg.liveTvInputId.isEmpty() || cfg.liveTvInputId.equals(info.getId()))) {
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                if (!info.isPassthroughInput() || info.isHidden(context)) continue;
                if (Integer.valueOf(-1).equals(InputSources.mapped(cfg.inputSources, info.getId()))) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(context);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = InputNames.tidy(info.loadCustomLabel(context));
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) {
                    View line = addSeparator(context, rows, density);
                    if (connectedOnly) fold(line, folded);
                }
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    for (TvInputInfo device : devices) {
                        CharSequence name = InputNames.tidy(device.loadCustomLabel(context));
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(context);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : tim.getInputState(deviceId);
                        View row = addInputRow(inflater, rows, R.drawable.ic_input, deviceLabel, state,
                                navigationSounds, dismissBeforeAction, () -> openInput(context, cfg, deviceId));
                        if (connectedOnly && InputFold.folds(true, state)) fold(row, folded);
                    }
                    continue;
                }
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                View row = addInputRow(inflater, rows, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        navigationSounds, dismissBeforeAction, () -> openInput(context, cfg, id));
                if (connectedOnly && InputFold.folds(hdmi, state)) fold(row, folded);
            }
        }
        if (!folded.isEmpty()) {
            View more = inflater.inflate(R.layout.item_input, rows, false);
            ((ImageView) more.findViewById(R.id.icon)).setImageResource(R.drawable.ic_input);
            ((TextView) more.findViewById(R.id.title)).setText(R.string.more_inputs);
            more.findViewById(R.id.status).setVisibility(View.GONE);
            more.findViewById(R.id.dot).setVisibility(View.GONE);
            more.setSoundEffectsEnabled(navigationSounds);
            more.setOnClickListener(v -> {
                for (View f : folded) f.setVisibility(View.VISIBLE);
                rows.removeView(more);
                for (View f : folded) {
                    if (f.isFocusable()) { f.requestFocus(); break; }
                }
            });
            rows.addView(more);
        }
        if (rows.getChildCount() == 0) {
            for (CoocaaSources.Source s : coocaa) {
                int icon = "HDMI".equals(s.name) ? R.drawable.ic_input
                        : "VOD".equals(s.name) ? R.drawable.ic_livetv : R.drawable.ic_av;
                addInputRow(inflater, rows, icon, s.label, -1, navigationSounds, dismissBeforeAction, () -> {
                    if (!CoocaaSources.open(context, s)) {
                        Toast.makeText(context, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        if (rows.getChildCount() == 0 && !cfg.sourcePanelService.isEmpty()) {
            addInputRow(inflater, rows, R.drawable.ic_input, context.getString(R.string.inputs), -1,
                    navigationSounds, dismissBeforeAction, () -> openSourcePanel(context, cfg));
        }
        Intent liveTv = liveTvIntent(context, cfg);
        if (liveTv != null && tunerId != null) {
            boolean configured = !cfg.liveTvInputId.isEmpty();
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(configured ? TvContract.buildChannelsUriForInput(tunerId)
                                    : TvContract.buildChannelUriForPassthroughInput(tunerId),
                            configured ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(cfg.liveTvPackage);
            if (tuned.resolveActivity(context.getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(context, rows, density);
            final Intent liveTvIntent = liveTv;
            addInputRow(inflater, rows, R.drawable.ic_livetv, context.getString(R.string.live_tv), -1,
                    navigationSounds, dismissBeforeAction, () -> openActivity(context, liveTvIntent));
        }
        return rows.getChildCount() > 0;
    }

    private static void fold(View view, List<View> folded) {
        view.setVisibility(View.GONE);
        folded.add(view);
    }

    private static View addSeparator(Context context, LinearLayout rows, float density) {
        View line = new View(context);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
        return line;
    }

    private static View addInputRow(LayoutInflater inflater, LinearLayout rows, int iconRes, String title,
                                    int state, boolean navigationSounds, Runnable dismissBeforeAction,
                                    Runnable action) {
        View row = inflater.inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setSoundEffectsEnabled(navigationSounds);
        row.setOnClickListener(v -> {
            if (dismissBeforeAction != null) dismissBeforeAction.run();
            action.run();
        });
        rows.addView(row);
        return row;
    }

    private static void openActivity(Context context, Intent intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(context, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
