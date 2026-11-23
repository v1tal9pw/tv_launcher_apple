package com.gathin.tvhome;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;

/** Remote-friendly glass shortcuts to the TV's own settings pages. */
final class QuickSettingsPanel extends FrameLayout {
    interface Listener {
        void onAction(int control);
        void onClosed(boolean restoreFocus);
        default void onOpened() { }
        default boolean onLauncherMenu() { return false; }
    }

    private static final PathInterpolator OPEN_INTERPOLATOR = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final PathInterpolator CLOSE_INTERPOLATOR = new PathInterpolator(0.32f, 0f, 0.67f, 0f);
    private static final PathInterpolator FOCUS_INTERPOLATOR = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Typeface TYPEFACE_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface TYPEFACE_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static float cachedWidthUnit = -1f, cachedWidthFontScale = -1f;
    private static int cachedWidthHome = -1, cachedPanelWidth = -1;
    private static String cachedWidthLocale;

    private final android.content.Context activity;
    private final ViewGroup home;
    private final int hostWidth, hostHeight;
    private final Listener listener;
    private Bitmap backdrop;
    private final QuickGlassDrawable.ShaderCache shaderCache = new QuickGlassDrawable.ShaderCache();
    private final ArrayList<View> glassViews = new ArrayList<>(12);
    private final ArrayList<QuickGlassDrawable> glassDrawables = new ArrayList<>(12);
    private final float unit, fontScale;
    private final boolean reducedMotion;
    private final boolean animationsEnabled;
    private final boolean navigationSounds;
    private final View dimmer;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final View homeShortcuts;
    private float shortcutAlpha;
    private int shortcutLayer;
    private View previousFocus;
    private final ArrayList<HomeChildState> homeChildren = new ArrayList<>();
    private final Rect tempRegion = new Rect();
    private TextView launcherSettingsLabel, allSettingsLabel, closeLabel;
    private ImageView launcherSettingsButton;
    private boolean updateBadgeVisible;
    private ValueAnimator transition;
    private ViewTreeObserver.OnPreDrawListener entryDraw;
    private Runnable pendingEntryAnimation;
    private final int[] settingsKeycodes;
    private float progress;
    private boolean reusable;
    private boolean showing;
    private boolean closing;
    private boolean suppressFocusAnimation;
    private boolean backDown;
    private boolean settingsDown;
    private Runnable afterClose;
    private int selected = R.id.quickAllSettings;
    private boolean restoreFocus = true;

    QuickSettingsPanel(android.content.Context activity, View home, boolean reducedMotion, boolean animations,
                       boolean navigationSounds, Listener listener) {
        this(activity, home, null, reducedMotion, animations, navigationSounds, listener);
    }

    QuickSettingsPanel(android.content.Context activity, View home, Bitmap prebuiltBackdrop,
                       boolean reducedMotion, boolean animations, boolean navigationSounds, Listener listener) {
        super(activity);
        this.activity = activity;
        this.home = (ViewGroup) home;
        this.hostWidth = home.getWidth();
        this.hostHeight = home.getHeight();
        this.listener = listener;
        int[] keycodes;
        try { keycodes = activity.getResources().getIntArray(R.array.settings_key_keycodes); }
        catch (RuntimeException ignored) { keycodes = new int[0]; }
        this.settingsKeycodes = keycodes;
        // Launcher settings' Animations off, like the system's animator scale at 0, opens and closes in place.
        animationsEnabled = animations && ValueAnimator.areAnimatorsEnabled();
        this.reducedMotion = reducedMotion || !animationsEnabled;
        this.navigationSounds = navigationSounds;
        if (navigationSounds) NavSound.prepare();
        unit = Math.min(home.getWidth() / 960f, home.getHeight() / 540f);
        fontScale = activity.getResources().getConfiguration().fontScale;
        homeShortcuts = home.findViewById(R.id.topButtons);
        shortcutAlpha = homeShortcuts.getAlpha();
        shortcutLayer = homeShortcuts.getLayerType();
        previousFocus = home.findFocus();
        // Use the pre-blurred wallpaper backdrop directly so opening never stalls the UI thread.
        this.backdrop = prebuiltBackdrop != null && !prebuiltBackdrop.isRecycled() ? prebuiltBackdrop : null;
        FrameLayout overlay = this;
        overlay.setOnClickListener(v -> dismiss());
        overlay.setSoundEffectsEnabled(navigationSounds);
        overlay.setFocusable(false);
        dimmer = new View(activity);
        dimmer.setBackground(new ColorDrawable(0x50030C1A));
        dimmer.setAlpha(0f);
        dimmer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.addView(dimmer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClickable(true);
        scroll.setSoundEffectsEnabled(navigationSounds);
        scroll.setFocusable(false);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(panelWidth(),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT | Gravity.TOP);
        position.setMargins(0, px(24), px(26), px(20));
        overlay.addView(scroll, position);
        content = column();
        content.setId(R.id.quickPanel);
        content.setBackgroundResource(R.drawable.quick_panel_background);
        content.setPadding(px(14), px(16), px(14), px(14));
        content.setClipChildren(false);
        // Focus scales a capsule into this padding. clipChildren=false alone does not stop
        // ViewGroup from clipping all its drawing to the padded content rectangle.
        content.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        showMain();
    }

    void setReusable(boolean value) {
        reusable = value;
    }

    boolean matchesHost(ViewGroup host) {
        return host == home && host.getWidth() == hostWidth && host.getHeight() == hostHeight;
    }

    /** Attaches and lays out the panel while invisible during idle time so first open costs ~0 ms. */
    void prewarm() {
        reusable = true;
        if (getParent() != null) return;
        showing = false;
        closing = false;
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setVisibility(View.INVISIBLE);
        home.addView(this, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.post(() -> {
            if (!showing && getParent() == home && content.getWidth() > 0) updateGlassRegions();
        });
    }

    void updateBackdrop(Bitmap next) {
        Bitmap valid = next != null && !next.isRecycled() ? next : null;
        if (valid == backdrop) return;
        backdrop = valid;
        for (int i = 0; i < glassDrawables.size(); i++) {
            glassDrawables.get(i).setBackdrop(valid);
        }
        if (content.getWidth() > 0) updateGlassRegions();
    }

    void detach() {
        reusable = false;
        removeEntryListener();
        cancelTransition();
        if (showing) finishDismiss();
        else if (getParent() == home) home.removeView(this);
    }

    void open(int focus) {
        removeEntryListener();
        cancelTransition();
        refreshUpdateBadge();
        showing = true;
        closing = false;
        restoreFocus = true;
        afterClose = null;
        selected = focus;
        backDown = false;
        settingsDown = false;
        shortcutAlpha = homeShortcuts.getAlpha();
        shortcutLayer = homeShortcuts.getLayerType();
        previousFocus = home.findFocus();
        applyProgress(0f);
        homeChildren.clear();
        for (int i = 0; i < home.getChildCount(); i++) {
            View child = home.getChildAt(i);
            if (child != this) homeChildren.add(new HomeChildState(child));
        }
        if (getParent() == home) {
            if (home.getChildAt(home.getChildCount() - 1) != this) bringToFront();
            setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setVisibility(View.VISIBLE);
        } else {
            setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setVisibility(View.VISIBLE);
            home.addView(this, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // One window submits both the real shortcuts and the panel in the same frame.
        if (home.isHardwareAccelerated()) homeShortcuts.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // Apply initial focus synchronously without running a child scale animation that would
        // invalidate scroll's hardware layer on every frame of the entrance slide.
        suppressFocusAnimation = true;
        try { focus(focus); }
        finally { suppressFocusAnimation = false; }
        if (animationsEnabled && home.isHardwareAccelerated()) scroll.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (content.getWidth() > 0) {
            beginEntry(focus);
            return;
        }
        entryDraw = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (content.getWidth() == 0) return true;
                removeEntryListener();
                if (!isShowing() || closing) return true;
                beginEntry(focus);
                return true;
            }
        };
        content.getViewTreeObserver().addOnPreDrawListener(entryDraw);
    }

    private void beginEntry(int focus) {
        updateGlassRegions();
        scroll.setPivotX(scroll.getWidth());
        scroll.setPivotY(Math.min(content.getHeight(), scroll.getHeight()) * 0.5f);
        suppressFocusAnimation = true;
        try { focus(focus); }
        finally { suppressFocusAnimation = false; }
        applyProgress(0f);
        if (!animationsEnabled) {
            applyProgress(1f);
            post(() -> { if (isShowing() && !closing) listener.onOpened(); });
            return;
        }
        // Keep alpha tiny (> 0) on frame 0: alpha == 0 causes RenderNode to skip building the
        // hardware FBO layer until frame 1, which would stall the start of the slide. 0.002f is
        // 0 in 8-bit RGBA (invisible) while forcing the GPU layer upload on frame 0.
        if (scroll.getLayerType() == View.LAYER_TYPE_HARDWARE) scroll.setAlpha(0.002f);
        pendingEntryAnimation = () -> {
            pendingEntryAnimation = null;
            if (!isShowing() || closing) return;
            animateTo(1f);
            post(() -> { if (isShowing() && !closing) listener.onOpened(); });
        };
        if (isAttachedToWindow()) postOnAnimation(pendingEntryAnimation);
        else post(pendingEntryAnimation);
    }

    private void applyProgress(float value) {
        progress = value;
        scroll.setAlpha(value);
        if (!reducedMotion) {
            scroll.setTranslationX(px(36) * (1f - value));
            float scale = 0.97f + 0.03f * value;
            scroll.setScaleX(scale);
            scroll.setScaleY(scale);
        } else {
            scroll.setTranslationX(0f);
            scroll.setScaleX(1f);
            scroll.setScaleY(1f);
        }
        dimmer.setAlpha(value);
        homeShortcuts.setAlpha(shortcutAlpha * (1f - value));
    }

    private void animateTo(float target) {
        cancelTransition();
        float distance = Math.abs(target - progress);
        if (!animationsEnabled || distance < .001f) {
            applyProgress(target);
            scroll.setLayerType(View.LAYER_TYPE_NONE, null);
            if (target == 0f) finishDismiss();
            return;
        }
        if (scroll.isHardwareAccelerated() && scroll.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            scroll.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        long duration = reducedMotion ? (target == 1f ? 180 : 140) : (target == 1f ? 340 : 210);
        transition = ValueAnimator.ofFloat(progress, target);
        transition.setDuration(Math.max(90, Math.round(duration * distance)));
        transition.setInterpolator(target == 1f ? OPEN_INTERPOLATOR : CLOSE_INTERPOLATOR);
        transition.addUpdateListener(animation -> applyProgress((float) animation.getAnimatedValue()));
        transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (transition != animation) return;
                transition = null;
                scroll.setLayerType(View.LAYER_TYPE_NONE, null);
                if (target == 0f) finishDismiss();
            }
        });
        transition.start();
    }

    private void cancelTransition() {
        if (pendingEntryAnimation != null) {
            removeCallbacks(pendingEntryAnimation);
            pendingEntryAnimation = null;
        }
        ValueAnimator previous = transition;
        transition = null;
        if (previous != null) {
            previous.removeAllListeners();
            previous.removeAllUpdateListeners();
            previous.cancel();
        }
    }

    private void removeEntryListener() {
        if (entryDraw == null) return;
        if (content.getViewTreeObserver().isAlive())
            content.getViewTreeObserver().removeOnPreDrawListener(entryDraw);
        entryDraw = null;
    }

    void dismiss() {
        if (!isShowing() || closing) return;
        closing = true;
        removeEntryListener();
        if (pendingEntryAnimation != null) {
            removeCallbacks(pendingEntryAnimation);
            pendingEntryAnimation = null;
        }
        if (activity instanceof Activity && (((Activity) activity).isFinishing() || ((Activity) activity).isDestroyed())) finishDismiss();
        else animateTo(0f);
    }

    private void finishDismiss() {
        removeEntryListener();
        cancelTransition();
        applyProgress(0f);
        scroll.setLayerType(View.LAYER_TYPE_NONE, null);
        homeShortcuts.setLayerType(shortcutLayer, null);
        if (!showing) return;
        showing = false;
        closing = false;
        for (HomeChildState child : homeChildren) child.restore();
        homeChildren.clear();
        // Restore focus before hiding/removal so Android cannot briefly focus a Home app instead.
        suppressFocusAnimation = true;
        try {
            if (restoreFocus && previousFocus != null && previousFocus.isAttachedToWindow())
                previousFocus.requestFocusFromTouch();
        } finally {
            suppressFocusAnimation = false;
        }
        if (reusable) {
            setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            setVisibility(View.INVISIBLE);
        } else if (getParent() == home) {
            home.removeView(this);
        }
        listener.onClosed(restoreFocus);
        Runnable action = afterClose;
        afterClose = null;
        if (action != null && (!(activity instanceof Activity) || (!((Activity) activity).isFinishing() && !((Activity) activity).isDestroyed()))) action.run();
    }

    boolean isShowing() { return showing && getParent() == home; }
    boolean isClosing() { return closing; }
    View getCurrentFocus() { return findFocus(); }

    void closeForAction(Runnable action) {
        if (closing) return;
        restoreFocus = false;
        afterClose = action;
        dismiss();
    }

    int selectedControl() { return selected; }

    private boolean isSettingsKey(int code) {
        if (code == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int listed : settingsKeycodes) if (listed == code) return true;
        return false;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (closing) return true;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) backDown = true;
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean shouldClose = backDown && !event.isCanceled();
                backDown = false;
                if (shouldClose) dismiss();
            }
            return true;
        }
        if (isSettingsKey(code)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) settingsDown = true;
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean shouldClose = settingsDown && !event.isCanceled();
                settingsDown = false;
                if (shouldClose) dismiss();
            }
            return true;
        }
        int direction;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: direction = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: direction = View.FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: direction = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: direction = View.FOCUS_DOWN; break;
            default: return super.dispatchKeyEvent(event);
        }
        // Keep remote focus within the panel, including at the edges of its explicit routes.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View current = getCurrentFocus();
            if (current == null) focus(selected);
            else {
                View next = current.focusSearch(direction);
                if (next != null && next != current && contains(next) && next.isFocusable()) {
                    NavSound.play(navigationSounds);
                    next.requestFocus(direction);
                }
            }
        }
        return true;
    }

    void closeWithoutFocus() {
        restoreFocus = false;
        afterClose = null;
        closing = true;
        finishDismiss();
    }

    private boolean contains(View view) {
        while (view != null && view != this)
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        return view == this;
    }

    /** Background content stays drawn but cannot receive focus or accessibility actions. */
    private static final class HomeChildState {
        final View view;
        final int accessibility, descendants;
        final boolean focusable;
        HomeChildState(View view) {
            this.view = view;
            accessibility = view.getImportantForAccessibility();
            focusable = view.isFocusable();
            descendants = view instanceof ViewGroup ? ((ViewGroup) view).getDescendantFocusability() : 0;
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            view.setFocusable(false);
            if (view instanceof ViewGroup)
                ((ViewGroup) view).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        void restore() {
            view.setImportantForAccessibility(accessibility);
            view.setFocusable(focusable);
            if (view instanceof ViewGroup) ((ViewGroup) view).setDescendantFocusability(descendants);
        }
    }

    private void showMain() {
        LinearLayout header = row();
        header.setGravity(Gravity.TOP);
        LinearLayout clock = column();
        clock.setPadding(px(8), px(2), 0, 0);
        TextClock date = clock(12, 0xC9FFFFFF);
        date.setId(R.id.quickDate);
        String datePattern = android.text.format.DateFormat.getBestDateTimePattern(
                activity.getResources().getConfiguration().getLocales().get(0), "EEE d MMM");
        date.setFormat12Hour(datePattern);
        date.setFormat24Hour(datePattern);
        clock.addView(date);
        TextClock time = clock(27, Color.WHITE);
        time.setId(R.id.quickTime);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-2, -2);
        timeParams.topMargin = px(8);
        clock.addView(time, timeParams);
        header.addView(clock, new LinearLayout.LayoutParams(0, px(74), 1));
        LinearLayout launcherSettings = column();
        launcherSettings.setGravity(Gravity.CENTER_HORIZONTAL);
        launcherSettings.setClipChildren(false);
        // As far from All settings as All settings is from Close; All settings sits 10 to the right.
        launcherSettings.setTranslationX(px(12));
        launcherSettingsButton = circle(R.id.quickLauncherSettings, R.drawable.ic_quick_launcher_settings,
                R.string.quick_launcher_settings, () -> listener.onAction(R.id.quickLauncherSettings));
        launcherSettings.addView(launcherSettingsButton);
        launcherSettingsButton.setOnLongClickListener(v -> !closing && listener.onLauncherMenu());
        refreshUpdateBadge();
        launcherSettingsLabel = text(activity.getString(R.string.quick_launcher_settings), 11, 500, 0xDBFFFFFF);
        launcherSettingsLabel.setId(R.id.quickLauncherSettingsLabel);
        launcherSettingsLabel.setGravity(Gravity.CENTER);
        launcherSettingsLabel.setMaxLines(2);
        launcherSettingsLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        launcherSettingsLabel.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams launcherLabelParams = new LinearLayout.LayoutParams(
                px(100), ViewGroup.LayoutParams.WRAP_CONTENT);
        launcherLabelParams.gravity = Gravity.CENTER_HORIZONTAL;
        launcherLabelParams.topMargin = px(6);
        launcherSettings.addView(launcherSettingsLabel, launcherLabelParams);
        header.addView(launcherSettings, new LinearLayout.LayoutParams(px(38), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout allSettings = column();
        allSettings.setGravity(Gravity.CENTER_HORIZONTAL);
        allSettings.setClipChildren(false);
        allSettings.setTranslationX(px(10));
        allSettings.addView(circle(R.id.quickAllSettings, R.drawable.ic_settings,
                R.string.quick_all_settings, () -> listener.onAction(R.id.quickAllSettings)));
        allSettingsLabel = text(activity.getString(R.string.quick_all_settings), 11, 500, 0xDBFFFFFF);
        allSettingsLabel.setId(R.id.quickAllSettingsLabel);
        allSettingsLabel.setGravity(Gravity.CENTER);
        allSettingsLabel.setMaxLines(2);
        allSettingsLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Keep the label's space so moving to a tile cannot shift the controls under focus.
        allSettingsLabel.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                px(100), ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.gravity = Gravity.CENTER_HORIZONTAL;
        labelParams.topMargin = px(6);
        allSettings.addView(allSettingsLabel, labelParams);
        header.addView(allSettings, new LinearLayout.LayoutParams(px(80), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout close = column();
        close.setGravity(Gravity.CENTER_HORIZONTAL);
        close.setClipChildren(false);
        close.addView(circle(R.id.quickClose, R.drawable.ic_quick_close, R.string.quick_close, this::dismiss));
        closeLabel = text(activity.getString(R.string.quick_close_label), 11, 500, 0xDBFFFFFF);
        closeLabel.setId(R.id.quickCloseLabel);
        closeLabel.setGravity(Gravity.CENTER);
        closeLabel.setMaxLines(2);
        closeLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        closeLabel.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams closeLabelParams = new LinearLayout.LayoutParams(px(52),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLabelParams.gravity = Gravity.CENTER_HORIZONTAL;
        closeLabelParams.topMargin = px(6);
        close.addView(closeLabel, closeLabelParams);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(px(38), ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.leftMargin = px(8);
        header.addView(close, closeParams);
        add(header, 0);

        int[][] tiles = {
                {R.id.quickScreensaver, R.drawable.ic_quick_screensaver, R.string.quick_screensaver},
                {R.id.quickNetwork, R.drawable.ic_wifi, R.string.wifi},
                {R.id.quickInputs, R.drawable.ic_source_switch, R.string.inputs},
                {R.id.quickAccessibility, R.drawable.ic_quick_accessibility, R.string.quick_accessibility},
                {R.id.quickBluetooth, R.drawable.ic_quick_bluetooth, R.string.quick_bluetooth},
                {R.id.quickApps, R.drawable.ic_quick_apps, R.string.quick_apps},
                {R.id.quickPicture, R.drawable.ic_quick_picture, R.string.quick_picture},
                {R.id.quickSound, R.drawable.ic_quick_sound, R.string.quick_sound},
                {R.id.quickClearAll, R.drawable.ic_quick_apps, R.string.quick_clear_background}
        };
        for (int i = 0; i < tiles.length; i += 2) {
            LinearLayout line = row();
            boolean single = i + 1 >= tiles.length;
            int count = single ? 1 : 2;
            for (int column = 0; column < count; column++) {
                int[] tile = tiles[i + column];
                String subtitle = tile[0] == R.id.quickScreensaver ? activity.getString(R.string.quick_start_now)
                        : tile[0] == R.id.quickBluetooth ? activity.getString(R.string.quick_accessories) : null;
                LinearLayout.LayoutParams lp = single
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                        : (column == 0 ? half() : halfRight());
                line.addView(pill(tile[0], tile[1], tile[2], subtitle), lp);
            }
            add(line, i == 0 ? 8 : 10);
        }
        connect(R.id.quickLauncherSettings, R.id.quickLauncherSettings, R.id.quickAllSettings,
                R.id.quickLauncherSettings, R.id.quickScreensaver);
        connect(R.id.quickAllSettings, R.id.quickLauncherSettings, R.id.quickClose,
                R.id.quickAllSettings, R.id.quickScreensaver);
        connect(R.id.quickClose, R.id.quickAllSettings, R.id.quickClose, R.id.quickClose, R.id.quickNetwork);
        for (int i = 0; i < tiles.length; i++) {
            boolean single = (i % 2 == 0) && (i + 1 >= tiles.length);
            int column = single ? 0 : i % 2;
            int left = single ? tiles[i][0] : tiles[i - column][0];
            int right = single ? tiles[i][0] : tiles[i - column + 1][0];
            int up = i < 2 ? (column == 0 ? R.id.quickAllSettings : R.id.quickClose) : tiles[i - 2][0];
            int down = i + 2 < tiles.length ? tiles[i + 2][0]
                    : (!single && column == 1 && i + 1 < tiles.length ? tiles[i + 1][0] : tiles[i][0]);
            connect(tiles[i][0], left, right, up, down);
        }
    }

    private TextClock clock(int size, int color) {
        TextClock clock = new TextClock(activity);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit * fontScale);
        clock.setTextColor(color);
        clock.setTypeface(TYPEFACE_MEDIUM);
        clock.setIncludeFontPadding(false);
        clock.setSingleLine(true);
        clock.setFocusable(false);
        clock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return clock;
    }

    private View pill(int id, int icon, int label, String summary) {
        LinearLayout button = row();
        button.setId(id);
        button.setMinimumHeight(px(68));
        button.setPadding(px(12), px(10), px(12), px(10));
        glass(button);
        FrameLayout symbol = new FrameLayout(activity);
        GradientDrawable white = new GradientDrawable();
        white.setShape(GradientDrawable.OVAL);
        white.setColor(Color.WHITE);
        symbol.setBackground(white);
        ImageView image = new ImageView(activity);
        image.setImageResource(icon);
        image.setColorFilter(0xFF007AFF);
        int size = px(24);
        symbol.addView(image, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        button.addView(symbol, new LinearLayout.LayoutParams(px(38), px(38)));
        LinearLayout copy = column();
        TextView title = text(activity.getString(label), 14, 600, Color.WHITE);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(title);
        if (summary != null) {
            TextView subtitle = text(summary, 11, 500, 0xDBFFFFFF);
            subtitle.setMaxLines(2);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = px(3);
            copy.addView(subtitle, subtitleParams);
        }
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        copyParams.leftMargin = px(9);
        button.addView(copy, copyParams);
        button.setContentDescription(activity.getString(label) + (summary != null ? ", " + summary : ""));
        makeButton(button, () -> listener.onAction(id));
        return button;
    }

    void refreshUpdateBadge() {
        boolean show = ReleaseUpdater.hasAvailableUpdate(activity);
        if (updateBadgeVisible != show) {
            updateBadgeVisible = show;
            if (launcherSettingsButton != null) launcherSettingsButton.invalidate();
        }
    }

    private ImageView circle(int id, int icon, int description, Runnable action) {
        final boolean isLauncherSettings = id == R.id.quickLauncherSettings;
        ImageView view = isLauncherSettings ? new ImageView(activity) {
            private final android.graphics.Paint badgePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                if (!updateBadgeVisible) return;
                float r = 4.6f * unit;
                float cx = getWidth() - 4.2f * unit;
                float cy = 4.2f * unit;
                badgePaint.setStyle(android.graphics.Paint.Style.FILL);
                badgePaint.setColor(0xFF20242C);
                canvas.drawCircle(cx, cy, r + 1.4f * unit, badgePaint);
                badgePaint.setColor(0xFFFF3B30);
                canvas.drawCircle(cx, cy, r, badgePaint);
            }
        } : new ImageView(activity);
        view.setId(id);
        view.setImageResource(icon);
        view.setColorFilter(Color.WHITE);
        view.setContentDescription(activity.getString(description));
        view.setPadding(px(10), px(10), px(10), px(10));
        view.setLayoutParams(new LinearLayout.LayoutParams(px(38), px(38)));
        glass(view);
        makeButton(view, action);
        return view;
    }

    private void glass(View view) {
        QuickGlassDrawable glass = new QuickGlassDrawable(backdrop, unit, shaderCache);
        view.setBackground(glass);
        glassViews.add(view);
        glassDrawables.add(glass);
    }

    private void updateGlassRegions() {
        if (backdrop == null || home.getWidth() == 0 || home.getHeight() == 0) return;
        int homeW = home.getWidth(), homeH = home.getHeight();
        for (int i = 0; i < glassViews.size(); i++) {
            View v = glassViews.get(i);
            if (v.getWidth() == 0) continue;
            // Walk pure layout coordinates up to home so scale/translation during entry never skews the crop.
            int left = 0, top = 0;
            View cursor = v;
            while (cursor != null && cursor != home) {
                left += cursor.getLeft() - cursor.getScrollX();
                top += cursor.getTop() - cursor.getScrollY();
                if (!(cursor.getParent() instanceof View)) break;
                cursor = (View) cursor.getParent();
            }
            tempRegion.set(left, top, left + v.getWidth(), top + v.getHeight());
            glassDrawables.get(i).setRegion(tempRegion, homeW, homeH);
        }
    }

    private void makeButton(View view, Runnable action) {
        view.setFocusable(true);
        view.setClickable(true);
        view.setDefaultFocusHighlightEnabled(false);
        view.setSoundEffectsEnabled(navigationSounds);
        if (view instanceof ViewGroup) ((ViewGroup) view).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        view.setOnClickListener(v -> {
            if (closing) return;
            selected = v.getId();
            NavSound.play(navigationSounds);
            action.run();
        });
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) selected = v.getId();
            if (v.getId() == R.id.quickLauncherSettings && launcherSettingsLabel != null)
                launcherSettingsLabel.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            if (v.getId() == R.id.quickAllSettings && allSettingsLabel != null)
                allSettingsLabel.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            if (v.getId() == R.id.quickClose && closeLabel != null)
                closeLabel.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            float scale = !reducedMotion && focused ? 1.025f : 1f;
            if (reducedMotion || suppressFocusAnimation) {
                v.animate().cancel();
                v.setScaleX(scale);
                v.setScaleY(scale);
            } else {
                v.animate().scaleX(scale).scaleY(scale)
                        .setDuration(150).setInterpolator(FOCUS_INTERPOLATOR).start();
            }
        });
    }

    private void connect(int id, int left, int right, int up, int down) {
        View view = content.findViewById(id);
        if (view == null) return;
        view.setNextFocusLeftId(left); view.setNextFocusRightId(right);
        view.setNextFocusUpId(up); view.setNextFocusDownId(down);
    }

    private void focus(int id) {
        if (!isShowing()) return;
        View view = content.findViewById(id);
        if (view == null) view = content.findViewById(R.id.quickAllSettings);
        if (view != null) view.requestFocusFromTouch();
    }

    private void add(View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = px(topMargin);
        content.addView(child, params);
    }

    private int panelWidth() {
        String locale = activity.getResources().getConfiguration().getLocales().toLanguageTags();
        int homeW = home.getWidth();
        if (cachedPanelWidth > 0 && cachedWidthHome == homeW
                && cachedWidthUnit == unit && cachedWidthFontScale == fontScale
                && locale.equals(cachedWidthLocale)) {
            return cachedPanelWidth;
        }
        // Measure translated labels at the actual font scale using a single Paint instead of
        // allocating a dozen throwaway TextViews on every panel open.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(14 * unit * fontScale);
        paint.setTypeface(TYPEFACE_MEDIUM);
        float widest = 0;
        for (int label : new int[]{R.string.quick_bluetooth,
                R.string.quick_picture, R.string.quick_sound, R.string.quick_screensaver,
                R.string.quick_apps, R.string.inputs, R.string.wifi, R.string.quick_accessibility, R.string.quick_accessories,
                R.string.settings, R.string.quick_start_now}) {
            widest = Math.max(widest, paint.measureText(activity.getString(label)));
        }
        // Each tile: icon 38 + padding 24 + gap 9. Panel: padding 20 + column gap 10.
        int desired = 2 * ((int) Math.ceil(widest) + px(38) + 2 * px(12) + px(9) + px(2))
                + 2 * px(10) + px(10);
        int result = Math.min(Math.max(px(336), desired), Math.max(1, homeW - px(52)));
        cachedPanelWidth = result;
        cachedWidthHome = homeW;
        cachedWidthUnit = unit;
        cachedWidthFontScale = fontScale;
        cachedWidthLocale = locale;
        return result;
    }

    private LinearLayout.LayoutParams half() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    private LinearLayout.LayoutParams halfRight() { LinearLayout.LayoutParams p = half(); p.leftMargin = px(10); return p; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); v.setClipChildren(false); return v; }
    private TextView text(String value, int size, int weight, int color) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextColor(color);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit * fontScale);
        text.setTypeface(weight >= 500 ? TYPEFACE_MEDIUM : TYPEFACE_REGULAR);
        text.setIncludeFontPadding(false);
        return text;
    }
    private int px(float value) { return Math.round(value * unit); }
}
