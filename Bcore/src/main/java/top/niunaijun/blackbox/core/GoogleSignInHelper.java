package top.niunaijun.blackbox.core;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.text.TextUtils;

import java.lang.reflect.Method;
import java.security.MessageDigest;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.BAccountManager;
import top.niunaijun.blackbox.utils.Slog;

public final class GoogleSignInHelper {

    private static final String TAG = "GoogleSignInHelper";

    public static void grantAccountVisibility(String virtualPackageName) {
        if (TextUtils.isEmpty(virtualPackageName)) return;
        try {
            Context ctx = BlackBoxCore.getContext();
            AccountManager am = AccountManager.get(ctx);
            Account[] accounts = am.getAccountsByType("com.google");
            for (Account account : accounts) {
                try {
                    am.setAccountVisibility(account, virtualPackageName, 2);
                    Slog.d(TAG, "Granted visibility of " + account.name + " to " + virtualPackageName);
                } catch (Exception e) {
                    Slog.w(TAG, "Could not set visibility for " + account.name + ": " + e.getMessage());
                }
            }
            for (Account account : accounts) {
                try {
                    am.setAccountVisibility(account, "com.android.vending", 2);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Slog.w(TAG, "grantAccountVisibility failed: " + e.getMessage());
        }
    }

    public static Bundle buildAuthOptions(String virtualPackageName, Bundle base) {
        Bundle opts = base != null ? new Bundle(base) : new Bundle();
        opts.putString("callerPackage",            virtualPackageName);
        opts.putString("appPackageName",           virtualPackageName);
        opts.putString("android.accounts.callerPackage", virtualPackageName);
        String sha1 = getRealAppSignatureSha1(virtualPackageName);
        if (!TextUtils.isEmpty(sha1)) {
            opts.putString("callerSignature", sha1);
            opts.putString("appSignatureHash", sha1);
        }
        return opts;
    }

    public static void getAuthTokenWithSpoofing(
            android.accounts.IAccountManagerResponse response,
            Account account,
            String authTokenType,
            boolean notifyOnAuthFailure,
            boolean expectActivityLaunch,
            Bundle options,
            String virtualPackageName) {

        Bundle enrichedOptions = buildAuthOptions(virtualPackageName, options);

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (callHiddenGetAuthToken(response, account, authTokenType,
                    notifyOnAuthFailure, expectActivityLaunch, enrichedOptions,
                    virtualPackageName)) {
                return;
            }
        }

        try {
            BAccountManager.get().getAuthToken(
                    response, account, authTokenType,
                    notifyOnAuthFailure, expectActivityLaunch, enrichedOptions);
        } catch (Exception e) {
            Slog.e(TAG, "getAuthToken fallback failed: " + e.getMessage());
        }
    }

    private static boolean callHiddenGetAuthToken(
            android.accounts.IAccountManagerResponse response,
            Account account,
            String authTokenType,
            boolean notifyOnAuthFailure,
            boolean expectActivityLaunch,
            Bundle options,
            String callerPackage) {

        try {
            Object service = getAccountManagerService();
            if (service == null) return false;

            Class<?> cls = service.getClass();
            int hostUid = android.os.Process.myUid();

            for (Method m : cls.getMethods()) {
                if (!"getAuthToken".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                boolean hasCallerPkg = false;
                for (Class<?> p : params) {
                    if (p == String.class) { hasCallerPkg = true; }
                }
                if (!hasCallerPkg || params.length < 7) continue;
                try {
                    if (params.length == 8) {
                        m.invoke(service, response, account, authTokenType,
                                notifyOnAuthFailure, expectActivityLaunch, options,
                                callerPackage, hostUid);
                        return true;
                    } else if (params.length == 9) {
                        m.invoke(service, response, account, authTokenType,
                                notifyOnAuthFailure, expectActivityLaunch, options,
                                callerPackage, hostUid, BActivityThread.getUserId());
                        return true;
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Hidden getAuthToken method failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "callHiddenGetAuthToken failed: " + e.getMessage());
        }
        return false;
    }

    private static Object getAccountManagerService() {
        try {
            return android.os.ServiceManager.getService(Context.ACCOUNT_SERVICE);
        } catch (Exception e) {
            try {
                Class<?> srm = Class.forName("android.os.ServiceManager");
                Method get = srm.getDeclaredMethod("getService", String.class);
                return get.invoke(null, Context.ACCOUNT_SERVICE);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    @SuppressWarnings("deprecation")
    public static String getRealAppSignatureSha1(String packageName) {
        try {
            Context ctx = BlackBoxCore.getContext();
            PackageInfo pi;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi = ctx.getPackageManager().getPackageInfo(
                        packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi.signingInfo == null) return "";
                Signature[] sigs = pi.signingInfo.getApkContentsSigners();
                if (sigs == null || sigs.length == 0) return "";
                return sha1Hex(sigs[0].toByteArray());
            } else {
                pi = ctx.getPackageManager().getPackageInfo(
                        packageName, PackageManager.GET_SIGNATURES);
                if (pi.signatures == null || pi.signatures.length == 0) return "";
                return sha1Hex(pi.signatures[0].toByteArray());
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.d(TAG, "Real app not found on host device: " + packageName);
        } catch (Exception e) {
            Slog.w(TAG, "getRealAppSignatureSha1 failed: " + e.getMessage());
        }
        return "";
    }

    private static String sha1Hex(byte[] certBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(certBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private GoogleSignInHelper() {}
}
