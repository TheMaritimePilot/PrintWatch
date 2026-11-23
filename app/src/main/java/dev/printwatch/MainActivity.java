package dev.printwatch;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
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
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private Updater updater;
    private LibraryServer libraryServer;
    private static WeakReference<MainActivity> current = new WeakReference<>(null);

    private static final long UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private final Runnable autoUpdate = new Runnable() {
        @Override public void run() {
            JSONObject c = config();
            if (c.optBoolean("autoUpdate", true)) updater.check(c.optString("updateToken", ""), true);
            ui.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLog(this);
        prefs = getSharedPreferences("printwatch", MODE_PRIVATE);
        current = new WeakReference<>(this);
        updater = new Updater(this, json -> ui.post(() ->
                web.evaluateJavascript("window.PW && PW.onUpdate(" + json + ")", null)));
        ui.postDelayed(autoUpdate, 60 * 1000L); // first check a minute after start
        applyOrientation(config().optString("orientation", "auto"));
        libraryServer = new LibraryServer(new LibraryServer.Handler() {
            @Override public byte[] asset(String path) throws IOException { return readAsset(path); }
            @Override public String getLibrary() throws Exception { return callJs("PWFil.remoteGet()"); }
            @Override public String applyOp(String json) throws Exception {
                String op = new JSONObject(json).toString(); // validated and re-encoded: safe to pass to the page
                return callJs("PWFil.remoteOp(" + op + ")");
            }
        });
        updateLibraryServer();
        Updater.handleInstallStatus(this, getIntent());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        }

        createDashboard();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Updater.handleInstallStatus(this, intent);
    }

    /** Writes any crash to a file, shown in Settings, Updates, "Update log" on the next start. */
    private static void installCrashLog(final Activity a) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous != null && previous.getClass().getName().startsWith("dev.printwatch")) return;
        final android.content.Context app = a.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Updater.log(app, "CRASH in thread " + t.getName() + ": " + Updater.stack(e));
            if (previous != null) previous.uncaughtException(t, e);
        });
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


    /** Creates the WebView that shows the dashboard (again after a renderer crash). */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createDashboard() {
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

    /** Called by the install receiver when Android reports a failed update. */
    static void reportUpdateFailure(final String message) {
        final MainActivity a = current.get();
        if (a == null) return;
        a.ui.post(() -> {
            JSONObject o = new JSONObject();
            try { o.put("state", "error"); o.put("message", message); } catch (Exception ignored) { }
            a.web.evaluateJavascript("window.PW && PW.onUpdate(" + o + ")", null);
        });
    }

    private final Map<String, JSONObject> sdCache = new HashMap<>();

    private File sdThumbDir() {
        return new File(getCacheDir(), "sdthumbs");
    }

    private void jsCall(final String script) {
        ui.post(() -> web.evaluateJavascript("window.PW && " + script, null));
    }

    private void updateLibraryServer() {
        if (config().optBoolean("webLibrary", false)) libraryServer.start(); else libraryServer.stop();
    }

    private byte[] readAsset(String path) throws IOException {
        try (InputStream in = getAssets().open("web/" + path)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Runs JavaScript in the dashboard and waits for its (string) result. */
    private String callJs(final String script) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        ui.post(() -> web.evaluateJavascript("(function(){try{return " + script + ";}catch(e){return null;}})()",
                (ValueCallback<String>) value -> { result.set(value); done.countDown(); }));
        if (!done.await(10, TimeUnit.SECONDS)) throw new Exception("timeout");
        String raw = result.get();
        if (raw == null || raw.equals("null")) throw new Exception("dashboard not ready");
        Object v = new JSONTokener(raw).nextValue(); // the JS string comes back JSON-encoded
        return String.valueOf(v);
    }

    private void applyOrientation(String o) {
        setRequestedOrientation("landscape".equals(o) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : "portrait".equals(o) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
    }

    // Small JSON documents (the filament library) stored in the app's private storage.
    private File dataFile(String name) {
        if (name == null || !name.matches("[a-z]{1,20}")) return null;
        return new File(getFilesDir(), "data-" + name + ".json");
    }

    private synchronized String readData(String name) {
        File f = dataFile(name);
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private synchronized void writeData(String name, String json) {
        File f = dataFile(name);
        if (f == null) return;
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f); // atomic: a crash never leaves a half-written library
    }

    private JSONObject config() {
        try { return new JSONObject(loadConfig()); } catch (Exception e) { return new JSONObject(); }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(autoUpdate);
        if (libraryServer != null) libraryServer.stop();
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
            JSONObject root = new JSONObject(loadConfig());
            JSONArray list = root.optJSONArray("printers");
            if (list == null) return;
            for (int i = 0; i < list.length(); i++) {
                PrinterConnection.Config c = PrinterConnection.Config.fromJson(list.getJSONObject(i));
                c.lightAuto = root.optBoolean("lightAuto", true);
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
                ui.post(() -> {
                    try {
                        startPrinters();
                        updateLibraryServer();
                    } catch (Throwable e) {
                        Updater.log(MainActivity.this, "Applying settings failed: " + Updater.stack(e));
                    }
                });
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

        /** Check GitHub now and install a newer version if there is one. */
        @JavascriptInterface
        public void checkUpdate() {
            try {
                Updater.log(MainActivity.this, "Check requested from settings");
                JSONObject c = config();
                updater.check(c.optString("updateToken", ""), true);
            } catch (Throwable e) {
                Updater.log(MainActivity.this, "checkUpdate failed: " + Updater.stack(e));
            }
        }

        /** Lists the print files on a printer's SD card; the answer comes back as PW.onSdList(index, json). */
        @JavascriptInterface
        public void sdList(final int index) {
            final PrinterConnection p = printer(index);
            new Thread(() -> {
                JSONObject res = new JSONObject();
                try {
                    if (p == null) throw new Exception("Printer not found");
                    res.put("files", p.listFiles());
                } catch (Throwable e) {
                    try { res.put("error", e.getMessage() == null ? e.toString() : e.getMessage()); } catch (Exception ignored) { }
                }
                jsCall("PW.onSdList(" + index + "," + res + ")");
            }, "sd-list").start();
        }

        /** Reads plates and filaments of one print file; answer: PW.onSdInfo(index, path, json). */
        @JavascriptInterface
        public void sdInfo(final int index, final String path, final long size) {
            final PrinterConnection p = printer(index);
            final String key = Integer.toHexString((path + "|" + size).hashCode()).replace('-', 'n');
            new Thread(() -> {
                JSONObject res;
                try {
                    if (p == null) throw new Exception("Printer not found");
                    synchronized (sdCache) { res = sdCache.get(key); }
                    if (res == null) {
                        res = p.projectInfo(path, sdThumbDir(), key);
                        synchronized (sdCache) { sdCache.put(key, res); }
                    }
                } catch (Throwable e) {
                    res = new JSONObject();
                    try { res.put("error", e.getMessage() == null ? e.toString() : e.getMessage()); } catch (Exception ignored) { }
                }
                jsCall("PW.onSdInfo(" + index + "," + JSONObject.quote(path) + "," + res + ")");
            }, "sd-info").start();
        }

        /** Lets the page write a line to the app log. */
        @JavascriptInterface
        public void log(String line) {
            if (line != null) Updater.log(MainActivity.this, line.length() > 500 ? line.substring(0, 500) : line);
        }

        /** The update log (and any crash) for the settings screen. */
        @JavascriptInterface
        public String updateLog() {
            try {
                java.io.File f = Updater.logFile(MainActivity.this);
                return f.exists() ? Updater.read(f) : "(empty)";
            } catch (Throwable e) {
                return "Could not read the log: " + e;
            }
        }

        /** Opens the newest APK on GitHub in the browser: the manual way to update. */
        @JavascriptInterface
        public void downloadUpdate() {
            launch(new Intent(Intent.ACTION_VIEW, Uri.parse(Updater.LATEST_APK)));
        }

        @JavascriptInterface
        public String updateStatus() {
            return updater.lastStatus();
        }

        /** Opens Android's "Install unknown apps" switch for PrintWatch (needed for updates). */
        @JavascriptInterface
        public void allowInstalls() {
            if (Build.VERSION.SDK_INT >= 26) {
                launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            }
        }

        @JavascriptInterface
        public boolean canInstall() {
            try {
                return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
            } catch (Throwable e) {
                return false;
            }
        }

        @JavascriptInterface
        public String loadData(String name) {
            return readData(name);
        }

        @JavascriptInterface
        public void saveData(String name, String json) {
            try {
                new JSONObject(json); // validate
                writeData(name, json);
            } catch (Exception ignored) { }
        }

        /** The address computers use for the filament library, or "" when it is off. */
        @JavascriptInterface
        public String webAddress() {
            return libraryServer != null && libraryServer.running() ? LibraryServer.address() : "";
        }

        @JavascriptInterface
        public void setOrientation(final String o) {
            ui.post(() -> applyOrientation(o));
        }

        /**
         * Android only lets an app open itself from the background (after an update or a reboot)
         * with the "Display over other apps" permission.
         */
        @JavascriptInterface
        public boolean canReopen() {
            return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void allowReopen() {
            launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
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
        /**
         * The page's rendering process died (out of memory, or a crash). Without this the whole
         * app would be closed; instead the dashboard is rebuilt and the printers keep running.
         */
        @Override
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            Updater.log(MainActivity.this, "Dashboard renderer stopped (crash: "
                    + (detail != null && detail.didCrash()) + "), rebuilding it");
            if (view == web) {
                web.destroy();
                createDashboard();
            } else {
                view.destroy();
            }
            return true;
        }

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
            if (path.startsWith("/sd/")) {
                String name = path.substring(4);
                File f = new File(sdThumbDir(), name);
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "max-age=3600");
                if (!name.matches("[0-9a-zn]+_\\d+\\.png") || !f.exists()) {
                    return new WebResourceResponse("image/png", null, 404, "Not found", headers,
                            new ByteArrayInputStream(new byte[0]));
                }
                try {
                    return new WebResourceResponse("image/png", null, 200, "OK", headers, new FileInputStream(f));
                } catch (IOException e) {
                    return null;
                }
            }
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
