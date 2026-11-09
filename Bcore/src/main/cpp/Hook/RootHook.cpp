/**
 * RootHook — fake-root support for non-rooted devices inside virtual space.
 *
 * What it does:
 *  1. Hooks getuid/geteuid/getgid/getegid → returns 0 so apps believe they
 *     are root.
 *  2. Hooks access()/stat()/lstat() for well-known su paths → pretends the
 *     su binary exists with rwxr-xr-x root:root permissions.
 *  3. Hooks execve() for su paths → transparently re-execs /system/bin/sh
 *     with the same command arguments.
 *
 * Why this works in virtual space:
 *  All virtual apps run under the same host-app UID, so a virtual "Game
 *  Guardian" can already read/write /proc/<game_pid>/mem of a sibling virtual
 *  game process without actual root.  The only blocker is that GG refuses to
 *  start because its root-detection checks fail.  These hooks fix every one of
 *  those checks without touching the kernel.
 */

#include "RootHook.h"
#include "../Dobby/dobby.h"
#include "../xdl.h"
#include <android/log.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <string.h>
#include <errno.h>
#include <alloca.h>

#define LOG_TAG "RootHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static bool g_enabled = false;

// ── su binary paths that apps probe ──────────────────────────────────────────

static const char* const kSuPaths[] = {
    "/system/xbin/su",
    "/system/bin/su",
    "/sbin/su",
    "/data/local/xbin/su",
    "/data/local/bin/su",
    "/data/local/su",
    "/su/bin/su",
    "/system/bin/failsafe/su",
    "/system/sd/xbin/su",
    nullptr
};

static bool is_su_path(const char* path) {
    if (!path) return false;
    for (int i = 0; kSuPaths[i]; i++) {
        if (strcmp(path, kSuPaths[i]) == 0) return true;
    }
    return false;
}

// ── UID / GID hooks ──────────────────────────────────────────────────────────

static uid_t (*orig_getuid)()  = nullptr;
static uid_t (*orig_geteuid)() = nullptr;
static gid_t (*orig_getgid)()  = nullptr;
static gid_t (*orig_getegid)() = nullptr;

static uid_t  fake_getuid()  { return 0; }
static uid_t  fake_geteuid() { return 0; }
static gid_t  fake_getgid()  { return 0; }
static gid_t  fake_getegid() { return 0; }

// ── access() hook ────────────────────────────────────────────────────────────

static int (*orig_access)(const char*, int) = nullptr;

static int fake_access(const char* path, int mode) {
    if (is_su_path(path)) {
        LOGD("FakeRoot: access() faked for: %s", path);
        return 0;
    }
    return orig_access ? orig_access(path, mode) : -1;
}

// ── stat() / lstat() hooks ───────────────────────────────────────────────────

static int (*orig_stat)(const char*, struct stat*)  = nullptr;
static int (*orig_lstat)(const char*, struct stat*) = nullptr;

static void fill_su_stat(struct stat* buf) {
    memset(buf, 0, sizeof(*buf));
    buf->st_mode = S_IFREG | 0755;
    buf->st_size = 8192;
    buf->st_uid  = 0;
    buf->st_gid  = 0;
}

static int fake_stat(const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_su_stat(buf); return 0; }
    return orig_stat ? orig_stat(path, buf) : -1;
}

static int fake_lstat(const char* path, struct stat* buf) {
    if (is_su_path(path)) { fill_su_stat(buf); return 0; }
    return orig_lstat ? orig_lstat(path, buf) : -1;
}

// ── execve() hook — redirect su → sh ─────────────────────────────────────────

static int (*orig_execve)(const char*, char* const[], char* const[]) = nullptr;

static int fake_execve(const char* path, char* const argv[], char* const envp[]) {
    if (!is_su_path(path)) {
        return orig_execve ? orig_execve(path, argv, envp) : -1;
    }
    LOGD("FakeRoot: execve() intercepted: %s", path);

    static const char* kSh = "/system/bin/sh";

    int argc = 0;
    while (argv[argc]) argc++;

    char** new_argv = (char**)alloca((argc + 2) * sizeof(char*));
    new_argv[0] = (char*)kSh;
    int new_argc = 1;

    for (int i = 1; i < argc; ) {
        const char* arg = argv[i];
        if (strcmp(arg, "-c") == 0) {
            new_argv[new_argc++] = (char*)"-c";
            if (i + 1 < argc) {
                new_argv[new_argc++] = argv[i + 1];
            }
            break;
        }
        if (arg[0] != '-') { i++; continue; }
        if (strcmp(arg, "-s") == 0) { i += 2; continue; }
        new_argv[new_argc++] = (char*)arg;
        i++;
    }
    new_argv[new_argc] = nullptr;

    return orig_execve(kSh, new_argv, envp);
}

// ── public API ───────────────────────────────────────────────────────────────

void RootHook::init() {
    if (g_enabled) return;
    g_enabled = true;

    LOGD("RootHook: installing fake-root hooks");

    void* libc = xdl_open("libc.so", XDL_DEFAULT);
    if (!libc) {
        LOGD("RootHook: cannot open libc.so");
        return;
    }

#define TRY_HOOK(sym, fake_fn, orig_ptr) do {                             \
    void* fn = xdl_sym(libc, sym, nullptr);                               \
    if (fn) {                                                              \
        DobbyHook(fn, (void*)(fake_fn), (void**)(orig_ptr));              \
        LOGD("RootHook: hooked %s", sym);                                 \
    } else {                                                               \
        LOGD("RootHook: symbol not found: %s", sym);                      \
    }                                                                      \
} while (0)

    TRY_HOOK("getuid",  fake_getuid,  &orig_getuid);
    TRY_HOOK("geteuid", fake_geteuid, &orig_geteuid);
    TRY_HOOK("getgid",  fake_getgid,  &orig_getgid);
    TRY_HOOK("getegid", fake_getegid, &orig_getegid);
    TRY_HOOK("access",  fake_access,  &orig_access);
    TRY_HOOK("stat",    fake_stat,    &orig_stat);
    TRY_HOOK("lstat",   fake_lstat,   &orig_lstat);
    TRY_HOOK("execve",  fake_execve,  &orig_execve);

#undef TRY_HOOK

    xdl_close(libc);
    LOGD("RootHook: all hooks installed");
}

bool RootHook::isEnabled() {
    return g_enabled;
}
