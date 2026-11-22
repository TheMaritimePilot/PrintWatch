package dev.printwatch;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from the GitHub releases of this project.
 * The release tag is "v1.0.N" and N is the app's versionCode. Every step is written to a small
 * log (Settings, Updates, "Update log") so problems can be diagnosed on the tablet.
 */
final class Updater {

    static final String REPO = "TheMaritimePilot/PrintWatch";
    static final String LATEST_APK = "https://github.com/" + REPO + "/releases/latest/download/PrintWatch.apk";
    static final String ACTION_INSTALL_STATUS = "dev.printwatch.INSTALL_STATUS";

    interface Listener {
        void onUpdateStatus(String json);
    }

    private final Context ctx;
    private final Listener listener;
    private volatile boolean busy;
    private volatile String lastStatus;

    Updater(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.lastStatus = status("idle", "", 0);
    }

    String lastStatus() { return lastStatus; }

    /** Checks GitHub and, if {@code install} is true and there is a newer version, installs it. */
    void check(final String token, final boolean install) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                report("checking", "Checking for updates…", 0);
                JSONObject rel = new JSONObject(new String(get(
                        "https://api.github.com/repos/" + REPO + "/releases/latest", token, "application/vnd.github+json"),
                        StandardCharsets.UTF_8));
                int latest = versionOf(rel.optString("tag_name"));
                String assetUrl = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) { assetUrl = a.optString("url"); break; }
                }
                int current = currentVersion();
                log(ctx, "Installed 1.0." + current + ", newest on GitHub 1.0." + latest);
                if (latest <= current || assetUrl == null) {
                    report("uptodate", "PrintWatch is up to date", latest);
                    return;
                }
                if (!install) {
                    report("available", "Version 1.0." + latest + " is available", latest);
                    return;
                }
                if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
                    report("permission", "Allow PrintWatch to install updates (button below)", latest);
                    return;
                }
                report("downloading", "Downloading version 1.0." + latest + "…", latest);
                byte[] apk = get(assetUrl, token, "application/octet-stream");
                log(ctx, "Downloaded " + apk.length + " bytes");
                report("installing", "Installing version 1.0." + latest + "…", latest);
                install(apk);
            } catch (Throwable e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log(ctx, "Failed: " + stack(e));
                if (m.contains("HTTP 404") || m.contains("HTTP 401") || m.contains("HTTP 403")) {
                    m = token == null || token.isEmpty()
                            ? "Can't see the releases on GitHub (HTTP error). Use \"Download from GitHub\" instead."
                            : "GitHub refused the token (" + m + ")";
                }
                report("error", "Update failed: " + m, 0);
            } finally {
                busy = false;
            }
        }, "updater").start();
    }

    private void install(byte[] apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        log(ctx, "Install session " + id + " created");
        try (PackageInstaller.Session session = pi.openSession(id)) {
            try (OutputStream out = session.openWrite("PrintWatch.apk", 0, apk.length)) {
                out.write(apk);
                session.fsync(out);
            }
            // The result comes back to the dashboard activity (Google's recommended pattern), which
            // can show Android's "Update this app?" confirmation when Android asks for it.
            Intent done = new Intent(ctx, MainActivity.class)
                    .setAction(ACTION_INSTALL_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getActivity(ctx, id, done, flags);
            session.commit(pending.getIntentSender());
            log(ctx, "Install session committed; waiting for Android");
        }
    }

    /**
     * Called by MainActivity with the result of an install session.
     * @return true if the intent was an install status
     */
    static boolean handleInstallStatus(android.app.Activity activity, Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return false;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        log(activity, "Android install status " + status + (msg == null ? "" : ": " + msg));
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    activity.startActivity(confirm);
                    log(activity, "Showing Android's update confirmation");
                } catch (Throwable e) {
                    log(activity, "Could not show the confirmation: " + e);
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            MainActivity.reportUpdateFailure("Update failed: " + (msg == null ? "status " + status : msg)
                    + ". Use \"Download from GitHub\" instead.");
        }
        return true;
    }

    /** After an update or a reboot this brings the dashboard back (needs "display over other apps"). */
    public static final class UpdatedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log(context, "Received " + intent.getAction());
            Intent i = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(i); } catch (Throwable ignored) { /* the system restarts the home screen anyway */ }
        }
    }

    int currentVersion() {
        try {
            return (int) ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        } catch (NoSuchMethodError e) {
            try {
                //noinspection deprecation
                return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
            } catch (PackageManager.NameNotFoundException ex) {
                return 0;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static int versionOf(String tag) {
        Matcher m = Pattern.compile("(\\d+)$").matcher(tag == null ? "" : tag);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** GET with manual redirects, so the token is only sent to GitHub and not to its file storage. */
    private static byte[] get(String url, String token, String accept) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "PrintWatch-updater");
            c.setRequestProperty("Accept", accept);
            boolean github = url.startsWith("https://api.github.com/");
            if (github && token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Bad redirect");
                url = loc;
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private void report(String state, String message, int latest) {
        log(ctx, state + ": " + message);
        lastStatus = status(state, message, latest);
        listener.onUpdateStatus(lastStatus);
    }

    private String status(String state, String message, int latest) {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("message", message);
            o.put("latest", latest);
            o.put("current", currentVersion());
        } catch (Throwable ignored) { }
        return o.toString();
    }

    // ------------------------------------------------------------------ log

    private static final Object LOG_LOCK = new Object();

    static File logFile(Context c) { return new File(c.getFilesDir(), "update-log.txt"); }

    /** Appends a line to the update log (kept small). */
    static void log(Context c, String line) {
        synchronized (LOG_LOCK) {
            try {
                File f = logFile(c);
                String old = f.exists() ? read(f) : "";
                if (old.length() > 12000) old = old.substring(old.length() - 8000);
                String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write((old + stamp + "  " + line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
        }
    }

    static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 1500 ? s.substring(0, 1500) : s;
    }
}
