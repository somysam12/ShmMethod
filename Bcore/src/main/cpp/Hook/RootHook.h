#pragma once

namespace RootHook {
    // Call once from enableFakeRoot() JNI.
    // After this every virtual-app thread sees getuid()==0 and
    // su execve() calls are transparently forwarded to /system/bin/sh.
    void init();
    bool isEnabled();
}
