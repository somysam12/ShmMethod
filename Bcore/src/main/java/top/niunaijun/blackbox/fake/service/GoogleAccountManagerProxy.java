package top.niunaijun.blackbox.fake.service;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class GoogleAccountManagerProxy extends ClassInvocationStub {
    public static final String TAG = "GoogleAccountManagerProxy";

    public GoogleAccountManagerProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        try {
            Context context = BlackBoxCore.getContext();
            if (context != null) {
                return AccountManager.get(context);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get AccountManager instance", e);
        }
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {}

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getAccounts")
    public static class GetAccounts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return new Account[0]; }
        }
    }

    @ProxyMethod("getAccountsByType")
    public static class GetAccountsByType extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return new Account[0]; }
        }
    }

    @ProxyMethod("getPassword")
    public static class GetPassword extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("getUserData")
    public static class GetUserData extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("addAccount")
    public static class AddAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("removeAccount")
    public static class RemoveAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return false; }
        }
    }

    @ProxyMethod("hasFeatures")
    public static class HasFeatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("getAuthToken")
    public static class GetAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("invalidateAuthToken")
    public static class InvalidateAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("peekAuthToken")
    public static class PeekAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("setPassword")
    public static class SetPassword extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("setUserData")
    public static class SetUserData extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("getAuthenticatorTypes")
    public static class GetAuthenticatorTypes extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return null; }
        }
    }

    @ProxyMethod("isAccountPresent")
    public static class IsAccountPresent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) { return false; }
        }
    }
}
