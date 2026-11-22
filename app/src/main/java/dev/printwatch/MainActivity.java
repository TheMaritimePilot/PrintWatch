package dev.printwatch;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen dashboard. Registered as a home screen (launcher), so once you pick it as the
 * default home app the tablet boots straight into it and the Home button always returns here.
 */
public class MainActivity extends Activity implements PrinterConnection.Callback {

    private static final String HOST = "printwatch.local";
    private static final String BASE = "https://" + HOST + "/";

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PrinterConnection> printers = new ArrayList<>();
    private final Map<Integer, String> pendingState = new HashMap<>();
    private final Map<Integer, Boolean> pendingFrame = new HashMap<>();
    private boolean flushScheduled;
    private SharedPreferences prefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("printwatch", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b0e13"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new LocalClient());
        setContentView(web);
        web.loadUrl(BASE + "index.html");
    }

    @Override
    protected void onStart() {
        super.onStart();
        startPrinters();
        hideSystemBars();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPrinters(); // screen off / another app in front: save battery and network
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Never leave the dashboard with Back. Let the page close its settings panel instead.
        web.evaluateJavascript("window.PW && PW.onBack()", null);
    }

    @Override
    protected void onDestroy() {
        stopPrinters();
        web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // ------------------------------------------------------------ printers

    private String loadConfig() {
        return prefs.getString("config", "{\"printers\":[]}");
    }

    private synchronized void startPrinters() {
        stopPrinters();
        try {
            JSONArray list = new JSONObject(loadConfig()).optJSONArray("printers");
            if (list == null) return;
            for (int i = 0; i < list.length(); i++) {
                PrinterConnection.Config c = PrinterConnection.Config.fromJson(list.getJSONObject(i));
                PrinterConnection p = new PrinterConnection(i, c, this, getCacheDir());
                printers.add(p);
                p.start();
            }
        } catch (Exception ignored) {
            // a broken config simply shows no printers; the settings screen can fix it
        }
    }

    private synchronized void stopPrinters() {
        for (PrinterConnection p : printers) p.stop();
        printers.clear();
    }

    private synchronized PrinterConnection printer(int i) {
        return i >= 0 && i < printers.size() ? printers.get(i) : null;
    }

    // Updates arrive on background threads; batch them onto the UI thread.
    @Override
    public void onState(int index, String json) {
        synchronized (pendingState) {
            pendingState.put(index, json);
            scheduleFlush();
        }
    }

    @Override
    public void onFrame(int index) {
        synchronized (pendingState) {
            pendingFrame.put(index, true);
            scheduleFlush();
        }
    }

    private void scheduleFlush() {
        if (flushScheduled) return;
        flushScheduled = true;
        ui.postDelayed(this::flush, 100);
    }

    private void flush() {
        StringBuilder js = new StringBuilder("if(window.PW){");
        synchronized (pendingState) {
            flushScheduled = false;
            for (Map.Entry<Integer, String> e : pendingState.entrySet()) {
                js.append("PW.onState(").append(e.getKey()).append(',').append(e.getValue()).append(");");
            }
            for (Integer i : pendingFrame.keySet()) js.append("PW.onFrame(").append(i).append(");");
            pendingState.clear();
            pendingFrame.clear();
        }
        js.append('}');
        web.evaluateJavascript(js.toString(), null);
    }

    // ------------------------------------------------------- JS bridge

    private final class Bridge {
        @JavascriptInterface
        public String getConfig() {
            return loadConfig();
        }

        @JavascriptInterface
        public void saveConfig(String json) {
            try {
                new JSONObject(json); // validate
                prefs.edit().putString("config", json).apply();
                ui.post(MainActivity.this::startPrinters);
            } catch (Exception ignored) { }
        }

        /** The page has (re)loaded and wants the current state of every printer. */
        @JavascriptInterface
        public void ready() {
            List<PrinterConnection> copy;
            synchronized (MainActivity.this) { copy = new ArrayList<>(printers); }
            for (PrinterConnection p : copy) p.reemit();
        }

        /** action: light, pause, resume, stop, speed, nozzle, bed, fan_part, fan_aux, fan_chamber */
        @JavascriptInterface
        public void command(int index, String action, String value) {
            PrinterConnection p = printer(index);
            if (p != null) p.command(action, value == null ? "" : value);
        }

        /** 0..1 to override the screen brightness, or a negative value for the system setting. */
        @JavascriptInterface
        public void setBrightness(final float value) {
            ui.post(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = value < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        : Math.max(0.01f, Math.min(1f, value));
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface
        public void playAlert() {
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), uri);
                if (r != null) r.play();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void openAndroidSettings() {
            launch(new Intent(Settings.ACTION_SETTINGS));
        }

        @JavascriptInterface
        public void openHomeSettings() {
            launch(new Intent(Settings.ACTION_HOME_SETTINGS));
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }
    }

    private void launch(final Intent intent) {
        ui.post(() -> {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) { }
            }
        });
    }

    // ------------------------------------- serve the page and camera frames

    private final class LocalClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !HOST.equals(request.getUrl().getHost()); // stay on our own page
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!HOST.equals(url.getHost())) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Blocked",
                        null, new ByteArrayInputStream(new byte[0]));
            }
            String path = url.getPath() == null ? "/" : url.getPath();
            boolean cam = path.startsWith("/cam/"), model = path.startsWith("/model/");
            if (cam || model) {
                int idx = -1;
                try {
                    idx = Integer.parseInt(path.substring(cam ? 5 : 7).replaceAll("\\..*$", ""));
                } catch (NumberFormatException ignored) { }
                PrinterConnection p = printer(idx);
                byte[] data = p == null ? null : (cam ? p.latestFrame() : p.modelImage());
                String type = cam ? "image/jpeg" : "image/png";
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-store");
                if (data == null) {
                    return new WebResourceResponse(type, null, 404, "Not available", headers,
                            new ByteArrayInputStream(new byte[0]));
                }
                return new WebResourceResponse(type, null, 200, "OK", headers, new ByteArrayInputStream(data));
            }
            if (path.equals("/")) path = "/index.html";
            if (path.contains("..")) return null;
            try {
                InputStream in = getAssets().open("web" + path);
                return new WebResourceResponse(mime(path), "utf-8", in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not found",
                        null, new ByteArrayInputStream(new byte[0]));
            }
        }
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
