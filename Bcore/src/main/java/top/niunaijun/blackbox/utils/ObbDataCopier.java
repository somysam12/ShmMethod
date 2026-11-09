package top.niunaijun.blackbox.utils;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import top.niunaijun.blackbox.core.env.BEnvironment;

public class ObbDataCopier {
    private static final String TAG = "ObbDataCopier";
    private static final int BUFFER_SIZE = 512 * 1024;

    public interface ProgressCallback {
        void onProgress(String phase, int percentage, long bytesDone, long totalBytes);
        void onComplete(boolean anyCopied, String message);
    }

    public static void copyInBackground(String packageName, int userId, ProgressCallback callback) {
        new Thread(() -> copyObbAndData(packageName, userId, callback),
                "ObbCopier-" + packageName).start();
    }

    public static void copyObbAndData(String packageName, int userId, ProgressCallback callback) {
        boolean anyCopied = false;
        String sdcard = Environment.getExternalStorageDirectory().getAbsolutePath();

        // 1. Copy OBB files
        File srcObb = new File(sdcard + "/Android/obb/" + packageName);
        File dstObb = BEnvironment.getExternalObbDir(packageName, userId);
        if (srcObb.exists() && srcObb.isDirectory()) {
            try {
                if (!dstObb.exists()) dstObb.mkdirs();
                // Skip if already copied
                File[] existing = dstObb.listFiles(f -> f.getName().endsWith(".obb"));
                if (existing == null || existing.length == 0) {
                    long total = getDirSize(srcObb);
                    long[] copied = {0};
                    int[] lastPct = {-1};
                    copyDirRecursive(srcObb, dstObb, total, copied, lastPct, "OBB", callback);
                    anyCopied = true;
                    Log.d(TAG, "OBB copied for " + packageName);
                } else {
                    Log.d(TAG, "OBB already in virtual space for " + packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "OBB copy failed for " + packageName + ": " + e.getMessage());
            }
        }

        // 2. Copy external data /sdcard/Android/data/<pkg>
        File srcExtData = new File(sdcard + "/Android/data/" + packageName);
        File dstExtData = BEnvironment.getExternalDataDir(packageName, userId);
        if (srcExtData.exists() && srcExtData.isDirectory()) {
            try {
                if (!dstExtData.exists()) dstExtData.mkdirs();
                long total = getDirSize(srcExtData);
                long[] copied = {0};
                int[] lastPct = {-1};
                copyDirRecursive(srcExtData, dstExtData, total, copied, lastPct, "External Data", callback);
                anyCopied = true;
                Log.d(TAG, "External data copied for " + packageName);
            } catch (Exception e) {
                Log.e(TAG, "External data copy failed for " + packageName + ": " + e.getMessage());
            }
        }

        // 3. Copy internal /data/data/<pkg> — best effort, requires root or special access
        File srcData = new File("/data/data/" + packageName);
        if (!srcData.exists() || !srcData.canRead()) {
            srcData = new File("/data/user/0/" + packageName);
        }
        File dstData = BEnvironment.getDataDir(packageName, userId);
        if (srcData.exists() && srcData.isDirectory() && srcData.canRead()) {
            try {
                if (!dstData.exists()) dstData.mkdirs();
                long total = getDirSize(srcData);
                long[] copied = {0};
                int[] lastPct = {-1};
                copyDirRecursive(srcData, dstData, total, copied, lastPct, "App Data", callback);
                anyCopied = true;
                Log.d(TAG, "Internal data copied for " + packageName);
            } catch (Exception e) {
                Log.e(TAG, "Internal data copy failed for " + packageName + ": " + e.getMessage());
            }
        }

        if (callback != null) {
            callback.onComplete(anyCopied, anyCopied ? "Data copy complete" : "No data to copy");
        }
    }

    private static void copyDirRecursive(File src, File dst, long totalSize,
            long[] copied, int[] lastPct, String phase, ProgressCallback callback) throws IOException {
        File[] files = src.listFiles();
        if (files == null) return;
        for (File file : files) {
            File dest = new File(dst, file.getName());
            if (file.isDirectory()) {
                if (!dest.exists()) dest.mkdirs();
                copyDirRecursive(file, dest, totalSize, copied, lastPct, phase, callback);
            } else {
                copyFile(file, dest, totalSize, copied, lastPct, phase, callback);
            }
        }
    }

    private static void copyFile(File src, File dst, long totalSize,
            long[] copiedRef, int[] lastPct, String phase, ProgressCallback callback) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                copiedRef[0] += n;
                if (totalSize > 0 && callback != null) {
                    int pct = (int) ((copiedRef[0] * 100L) / totalSize);
                    if (pct != lastPct[0]) {
                        lastPct[0] = pct;
                        callback.onProgress(phase, pct, copiedRef[0], totalSize);
                    }
                }
            }
        }
    }

    private static long getDirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            size += f.isDirectory() ? getDirSize(f) : f.length();
        }
        return size;
    }
}
