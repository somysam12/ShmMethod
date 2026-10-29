package com.mundo.core;

import android.os.Binder;
import android.os.Build;
import android.os.Process;
/*
import com.android.apksig.ApkVerifier;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;*/
import androidx.annotation.Keep;
import android.content.Context;
import java.io.File;
import java.util.List;
import dalvik.system.DexFile;
import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;
import com.mundo.utils.compat.DexFileCompat;

public class VNative {
    
    public static final String TAG = "VNative";
    private static boolean isInjected = false;
    public static String libtarget = "libbgmi.so";

    static {
        System.loadLibrary("id1z5u62rg48rrchhw3");
        File file = new File(MundoCore.getContext().getFilesDir(), "loader/" + libtarget);
        if (file.exists()) {
            System.load(file.getAbsolutePath());
        }
    }

    public static native void init(int apiLevel);
    public static native void enableIO();
    public static native void addIORule(String targetPath, String relocatePath);
    public static native void hideXposed();
    
    @Keep
    public static int getCallingUid(int origCallingUid) {
        if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID) return origCallingUid;
        if (origCallingUid > Process.LAST_APPLICATION_UID) return origCallingUid;
        if (origCallingUid == MundoCore.getHostUid()) {
            if(BActivityThread.getAppPackageName().equals("com.google.android.gms")){
                return Process.ROOT_UID;
            }
            if(BActivityThread.getAppPackageName().equals("com.google.android.webview")){
                return Process.myUid();
            }
            return BActivityThread.getCallingBUid();
        }
        return origCallingUid;
    }

    @Keep
    public static String redirectPath(String path) {
        return VCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return VCore.get().redirectPath(path);
    }
    
    /*
    public void runApk(Context ctx) {
        try {
            File apkFile = new File(ctx.getApplicationInfo().sourceDir);
            ApkVerifier verifier = new ApkVerifier.Builder(apkFile).build();
            ApkVerifier.Result result = verifier.verify();
            if (!result.isVerified()) {
                throw new SecurityException("APK verification failed");
            }
            if (Build.VERSION.SDK_INT >= 24) {
                result.getSignerCertificates().forEach(new Consumer<X509Certificate>() {
                    @Override
                    public void accept(X509Certificate cert) {
                        verifyCertificateSignature(cert);
                    }
                });
            }
        } catch (Exception e) {
            throw new SecurityException("APK verification error", e);
        }
    }

    private void verifyCertificateSignature(X509Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String hash = bytesToHex(md.digest(cert.getEncoded()));
            if (!SHA256.equalsIgnoreCase(hash)) {
                throw new SecurityException("Certificate signature mismatch");
            }
        } catch (Exception e) {
            throw new RuntimeException("Certificate verification error", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }*/

}
