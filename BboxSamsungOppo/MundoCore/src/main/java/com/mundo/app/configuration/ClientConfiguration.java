package com.mundo.app.configuration;

import java.io.File;

public abstract class ClientConfiguration {
    
    public abstract String getHostPackageName();
    
    public boolean setHideRoot() {
        return false;
    }
    
    public boolean isEnableDaemonService() {
        return true;
    }

    public boolean isEnableLauncherActivity() {
        return false;
    }

    public boolean requestInstallPackage(File file) {
        return false;
    }
}
