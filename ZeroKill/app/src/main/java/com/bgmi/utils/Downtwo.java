package com.bgmi.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


@SuppressWarnings({"unused","deprecation"})
public class Downtwo {

    private static final String TAG      = "FF_DL";
    private static final String PREF     = "ff_lib_dl";
    private static final String PREF_VER = "cached_ver";
    private static final String SO_NAME  = "libZeroKill_payload.so";

    public static native String Version();
    public static native String Link();

    public interface Callback             { void onComplete(boolean success); }
    public interface ProgressListener     { void onProgress(int pct); }
    public interface SizeProgressListener { void onProgress(int pct, long done, long total); }

    private final Context  ctx;
    private final Callback cb;
    private ProgressListener     pl;
    private SizeProgressListener spl;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler         main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;

    public Downtwo(Context ctx, Callback cb) { this.ctx = ctx; this.cb = cb; }
    public void setProgressListener(ProgressListener l)         { this.pl = l; }
    public void setSizeProgressListener(SizeProgressListener l) { this.spl = l; }

    public void setCredentials(String key, String serial, String pkg) { /* no-op */ }

    public void cancel() { cancelled = true; }

    public void execute(String... ignored) {
        exec.execute(() -> {
            boolean ok = false;
            try { ok = doWork(); }
            catch (Throwable t) { Log.e(TAG, "Fatal: " + t.getMessage()); }
            final boolean result = ok;
            postToMain(() -> { if (cb != null) cb.onComplete(result); });
            exec.shutdown();
        });
    }

    private boolean doWork() {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        File loaderDir = new File(ctx.getFilesDir(), "loader");
        File targetSo  = new File(loaderDir, SO_NAME);

        try {
            String bust = "?t=" + System.currentTimeMillis();

            String remoteVer = null;
            try {
                String versionUrl = Version();
                if (versionUrl != null && !versionUrl.isEmpty()) {
                    remoteVer = fetchVersion(versionUrl + bust);
                }
            } catch (Exception e) {
                Log.e(TAG, "Version check: " + e.getMessage());
            }

            String  localVer = prefs.getString(PREF_VER, "");
            File[] existingSo = loaderDir.exists() ? loaderDir.listFiles(f -> f.getName().endsWith(".so")) : null;
            boolean soOk     = existingSo != null && existingSo.length > 0 && existingSo[0].length() > 1024;
            boolean match    = remoteVer != null && !remoteVer.isEmpty() && remoteVer.equals(localVer);

            Log.d(TAG, "remote=[" + remoteVer + "] local=[" + localVer + "] soOk=" + soOk);

            if (match && soOk) { Log.d(TAG, "Up to date"); return true; }

            if (loaderDir.exists()) {
                File[] files = loaderDir.listFiles();
                if (files != null) for (File f : files) f.delete();
            }
            loaderDir.mkdirs();

            boolean ok = downloadFile(Link(), targetSo);

            if (ok) {
                File[] soFiles = loaderDir.listFiles(f -> f.getName().endsWith(".so"));
                if (soFiles != null) {
                    for (File so : soFiles) {
                        so.setReadable(true, false);
                        so.setExecutable(true, false);
                    }
                }
            }

            if (ok) {
                if (remoteVer != null) prefs.edit().putString(PREF_VER, remoteVer).apply();
                Log.d(TAG, "Download OK: " + targetSo.length() + "B");
            } else {
                prefs.edit().remove(PREF_VER).apply();
                Log.e(TAG, "Download FAILED");
            }
            return ok;

        } catch (Exception e) {
            Log.e(TAG, "Fatal: " + e.getMessage());
            prefs.edit().remove(PREF_VER).apply();
            return false;
        }
    }

    private String fetchVersion(String urlStr) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(urlStr).openConnection();
            con.setInstanceFollowRedirects(true);
            con.setConnectTimeout(10000); con.setReadTimeout(10000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            con.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
            con.connect();
            if (con.getResponseCode() != 200) return null;
            Scanner sc = new Scanner(con.getInputStream(), "UTF-8");
            StringBuilder sb = new StringBuilder();
            while (sc.hasNextLine()) sb.append(sc.nextLine().trim());
            sc.close();
            String ver = sb.toString().trim();
            return ver.isEmpty() ? null : ver;
        } catch (Exception e) {
            Log.e(TAG, "fetchVersion: " + e.getMessage());
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private void postToMain(Runnable r) {
        if (cancelled) return;
        main.post(() -> { if (!cancelled) r.run(); });
    }

    private boolean downloadFile(String url, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Cache-Control", "no-cache, no-store");
        c.connect();
        if (c.getResponseCode() != 200) {
            Log.e(TAG, "Download HTTP " + c.getResponseCode());
            c.disconnect();
            return false;
        }
        long total = c.getContentLengthLong(), done = 0;
        File tmpFile = new File(dest.getParent(), dest.getName() + ".tmp");
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(tmpFile);
        byte[] buf = new byte[32768]; int n;
        while ((n = in.read(buf)) != -1) {
            if (cancelled) break;
            out.write(buf, 0, n); done += n;
            if (total > 0) {
                final int  pct = (int)((done * 100L) / total);
                final long d2  = done, t2 = total;
                postToMain(() -> {
                    if (pl  != null) pl.onProgress(pct);
                    if (spl != null) spl.onProgress(pct, d2, t2);
                });
            }
        }
        out.flush(); out.close(); in.close(); c.disconnect();

        if (!tmpFile.exists() || tmpFile.length() < 4) return false;

        if (isZipFile(tmpFile)) {
            File loaderDir = dest.getParentFile();
            boolean extracted = extractSoFromZip(tmpFile, loaderDir);
            tmpFile.delete();
            if (!extracted) return false;
            File[] soFiles = loaderDir.listFiles(f -> f.getName().endsWith(".so"));
            return soFiles != null && soFiles.length > 0 && soFiles[0].length() > 1024;
        }

        dest.delete();
        tmpFile.renameTo(dest);
        return dest.exists() && dest.length() > 1024;
    }

    private boolean isZipFile(File f) {
        try {
            byte[] magic = new byte[4];
            FileInputStream fis = new FileInputStream(f);
            int r = fis.read(magic);
            fis.close();
            return r == 4 && magic[0] == 0x50 && magic[1] == 0x4B
                         && magic[2] == 0x03 && magic[3] == 0x04;
        } catch (Exception e) { return false; }
    }

    private boolean extractSoFromZip(File zipFile, File loaderDir) {
        boolean found = false;
        try {
            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
                if (name.endsWith(".so") && !entry.isDirectory()) {
                    File out = new File(loaderDir, name);
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[32768]; int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush(); fos.close();
                    out.setReadable(true, false);
                    out.setExecutable(true, false);
                    Log.d(TAG, "Extracted " + name + " (" + out.length() + "B)");
                    found = true;
                }
            }
            zis.close();
            if (!found) Log.e(TAG, "No .so found in ZIP");
            return found;
        } catch (Exception e) {
            Log.e(TAG, "ZIP extract: " + e.getMessage());
            return false;
        }
    }
}
