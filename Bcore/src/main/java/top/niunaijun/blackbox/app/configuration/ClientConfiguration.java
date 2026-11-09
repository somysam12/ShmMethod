package top.niunaijun.blackbox.app.configuration;

import java.io.File;


public abstract class ClientConfiguration {

    public boolean isHideRoot() {
        return false;
    }

    /**
     * When true, every virtual app in this space sees getuid()==0 and can
     * locate / execute a virtual su binary backed by /system/bin/sh.
     * Requires a non-rooted device — works because all virtual processes
     * share the same host-app UID and can already access each other's
     * /proc/<pid>/mem.  Mutually exclusive with isHideRoot().
     */
    public boolean isFakeRoot() {
        return false;
    }



    public abstract String getHostPackageName();

    public boolean isEnableDaemonService() {
        return true;
    }
