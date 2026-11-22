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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from the GitHub releases of this project.
 * The release tag is "v1.0.N" and N is the app's versionCode. On Android 12+ the update
 * installs without any tap (after "Install unknown apps" was allowed once); on older
 * Android versions Android shows one "Update" confirmation.
 */
final class Updater {

    static final String REPO = "TheMaritimePilot/PrintWatch";

    interface Listener {
        void onUpdateStatus(String json);
    }

    private final Context ctx;
    private final Listener listener;
    private volatile boolean busy;
    private volatile String lastStatus = status("idle", "", 0);

    Updater(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
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
                report("installing", "Installing version 1.0." + latest + "…", latest);
                install(apk);
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (m.contains("HTTP 404") || m.contains("HTTP 401") || m.contains("HTTP 403")) {
                    m = token == null || token.isEmpty()
                            ? "Can't see the releases: the repository is private. Add a GitHub token in settings."
                            : "GitHub refused the token (" + m + ")";
                }
                report("error", m, 0);
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
        try (PackageInstaller.Session session = pi.openSession(id)) {
            try (OutputStream out = session.openWrite("PrintWatch.apk", 0, apk.length)) {
                out.write(apk);
                session.fsync(out);
            }
            Intent done = new Intent(ctx, InstallResultReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, done, flags);
            session.commit(pending.getIntentSender());
        }
    }

    /** Receives the result of the install; asks the user to confirm when Android requires it. */
    public static final class InstallResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                MainActivity.reportUpdateFailure("Update failed: " + (msg == null ? "status " + status : msg));
            }
        }
    }

    /** After an update Android restarts the app; this brings the dashboard back. */
    public static final class UpdatedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent i = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(i); } catch (Exception ignored) { /* the system restarts the home screen anyway */ }
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
        lastStatus = status(state, message, latest);
        listener.onUpdateStatus(lastStatus);
    }

    private String status(String state, String message, int latest) {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("message", message);
            o.put("latest", latest);
            o.put("current", ctx == null ? 0 : currentVersion());
        } catch (Exception ignored) { }
        return o.toString();
    }
}
