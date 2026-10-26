package com.helm.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

/**
 * Hosts the {@link Shade} over other apps, with the window behind blurred. Opened by the assistant
 * shortcut (long-press Home, when Helm is the digital assistant app) and by Helm when home isn't on
 * screen. On home, the shade lives inside the launcher instead.
 */
public final class Overlay extends Activity implements Shade.Host {
    static final String PANEL = Shade.PANEL, RECENTS = Shade.RECENTS;
    private static final String EXTRA_MODE = "mode";

    static void open(Context c, String mode) {
        Intent i = new Intent(c, Overlay.class).putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            c.startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    private FrameLayout root;
    private Shade shade;
    private boolean done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Palette p = Palette.resolve(this);
        Ui.edgeToEdge(getWindow());
        WindowManager wm = getSystemService(WindowManager.class);
        try {
            if (wm != null && wm.isCrossWindowBlurEnabled()) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.setBlurBehindRadius(Ui.dp(36));
                getWindow().setAttributes(lp);
            }
        } catch (RuntimeException ignored) {
            // No blur on this build: the tint alone carries it.
        }
        noTransition(false);

        root = new FrameLayout(this);
        setContentView(root);
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(p.dark ? 0 : light, light);
        }
        shade = new Shade(this, p, this, modeOf(getIntent()), false);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets sys = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            shade.setInsets(sys.top, sys.bottom);
            return insets;
        });
        shade.setProgress(0f);
        shade.open();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (!Sheet.dismissIn(this)) shade.close();
        });
    }

    private static String modeOf(Intent i) {
        if (i == null) return PANEL;
        if (Intent.ACTION_ASSIST.equals(i.getAction())) return RECENTS;
        String m = i.getStringExtra(EXTRA_MODE);
        return RECENTS.equals(m) ? RECENTS : PANEL;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (shade == null || done) return;
        if (shade.closing()) shade.reopen(modeOf(intent));
        else shade.setMode(modeOf(intent));
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Like the real shade: leaving (Home, another app) puts it away.
        if (!isChangingConfigurations() && !isFinishing()) shadeClosed();
    }

    @SuppressWarnings("deprecation")
    private void noTransition(boolean closing) {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(closing ? OVERRIDE_TRANSITION_CLOSE : OVERRIDE_TRANSITION_OPEN, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    @Override
    public void shadeClosed() {
        if (done) return;
        done = true;
        finish();
        noTransition(true);
    }

    @Override
    public ViewGroup sheetHost() { return root; }

    @Override
    public void lockScreen() {
        if (HelmAccessibility.act(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return;
        Toast.makeText(this, "Turn on Helm's accessibility service to lock from here", Toast.LENGTH_LONG).show();
    }
}
