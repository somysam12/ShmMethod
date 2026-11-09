/*
 * rootd — privileged memory-access daemon
 *
 * Runs as root (via `su`), listens on a Unix-domain socket, and serves
 * /proc/<pid>/mem read/write requests from virtual-app processes that share
 * the host-app UID but cannot access system-process memory directly.
 *
 * Socket path: /dev/rootd.sock   (chmod 0777 so all virtual apps can reach it)
 *
 * Wire protocol (all fields in native byte order):
 *   Request  { int cmd; int pid; long offset; int length; char data[65536]; }
 *   Response { int status; int length; char data[65536]; }
 *
 * Commands:
 *   CMD_PING      (4): respond STATUS_OK, length=0
 *   CMD_READ_MEM  (1): open /proc/pid/mem, pread(offset, length) -> data
 *   CMD_WRITE_MEM (2): open /proc/pid/mem writable, pwrite(offset, data, length)
 *   CMD_EXEC_CMD  (3): run shell command, read stdout -> data
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <android/log.h>

#define TAG        "rootd"
#define SOCK_PATH  "/dev/rootd.sock"
#define MAX_DATA   65536
#define BACKLOG    8

#define CMD_READ_MEM  1
#define CMD_WRITE_MEM 2
#define CMD_EXEC_CMD  3
#define CMD_PING      4

#define STATUS_OK    0
#define STATUS_ERROR 1

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#pragma pack(push, 1)
typedef struct {
    int  cmd;
    int  pid;
    long offset;
    int  length;
    char data[MAX_DATA];
} Request;

typedef struct {
    int  status;
    int  length;
    char data[MAX_DATA];
} Response;
#pragma pack(pop)

static int read_exact(int fd, void* buf, size_t n) {
    size_t done = 0;
    while (done < n) {
        ssize_t r = read(fd, (char*)buf + done, n - done);
        if (r <= 0) return -1;
        done += (size_t)r;
    }
    return 0;
}

static int write_exact(int fd, const void* buf, size_t n) {
    size_t done = 0;
    while (done < n) {
        ssize_t w = write(fd, (const char*)buf + done, n - done);
        if (w <= 0) return -1;
        done += (size_t)w;
    }
    return 0;
}

static void handle_read_mem(const Request* req, Response* resp) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", req->pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        LOGE("open %s failed: %s", path, strerror(errno));
        resp->status = STATUS_ERROR;
        resp->length = 0;
        return;
    }
    int len = req->length;
    if (len <= 0 || len > MAX_DATA) len = MAX_DATA;
    ssize_t n = pread(fd, resp->data, (size_t)len, (off_t)req->offset);
    close(fd);
    if (n < 0) {
        LOGE("pread %s @ %ld failed: %s", path, req->offset, strerror(errno));
        resp->status = STATUS_ERROR;
        resp->length = 0;
    } else {
        resp->status = STATUS_OK;
        resp->length = (int)n;
    }
}

static void handle_write_mem(const Request* req, Response* resp) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", req->pid);
    int fd = open(path, O_WRONLY);
    if (fd < 0) {
        LOGE("open(wronly) %s failed: %s", path, strerror(errno));
        resp->status = STATUS_ERROR;
        resp->length = 0;
        return;
    }
    int len = req->length;
    if (len <= 0 || len > MAX_DATA) len = MAX_DATA;
    ssize_t n = pwrite(fd, req->data, (size_t)len, (off_t)req->offset);
    close(fd);
    if (n < 0) {
        LOGE("pwrite %s @ %ld failed: %s", path, req->offset, strerror(errno));
        resp->status = STATUS_ERROR;
        resp->length = 0;
    } else {
        resp->status = STATUS_OK;
        resp->length = (int)n;
    }
}

static void handle_exec_cmd(const Request* req, Response* resp) {
    char cmd[MAX_DATA + 1];
    int cmdlen = req->length > 0 ? req->length : (int)strlen(req->data);
    if (cmdlen >= MAX_DATA) cmdlen = MAX_DATA - 1;
    memcpy(cmd, req->data, (size_t)cmdlen);
    cmd[cmdlen] = '\0';

    FILE* fp = popen(cmd, "r");
    if (!fp) {
        LOGE("popen(%s) failed: %s", cmd, strerror(errno));
        resp->status = STATUS_ERROR;
        resp->length = 0;
        return;
    }
    int n = (int)fread(resp->data, 1, MAX_DATA, fp);
    pclose(fp);
    resp->status = STATUS_OK;
    resp->length = n;
}

static void handle_client(int cfd) {
    Request  req;
    Response resp;

    while (1) {
        memset(&req,  0, sizeof(req));
        memset(&resp, 0, sizeof(resp));

        size_t hdr_size = sizeof(int)*2 + sizeof(long) + sizeof(int);
        if (read_exact(cfd, &req, hdr_size) < 0) break;

        if ((req.cmd == CMD_WRITE_MEM || req.cmd == CMD_EXEC_CMD) && req.length > 0) {
            int payload = req.length;
            if (payload > MAX_DATA) payload = MAX_DATA;
            if (read_exact(cfd, req.data, (size_t)payload) < 0) break;
        }

        switch (req.cmd) {
            case CMD_PING:
                resp.status = STATUS_OK;
                resp.length = 0;
                break;
            case CMD_READ_MEM:
                handle_read_mem(&req, &resp);
                break;
            case CMD_WRITE_MEM:
                handle_write_mem(&req, &resp);
                break;
            case CMD_EXEC_CMD:
                handle_exec_cmd(&req, &resp);
                break;
            default:
                LOGE("unknown cmd %d", req.cmd);
                resp.status = STATUS_ERROR;
                resp.length = 0;
                break;
        }

        size_t resp_hdr = sizeof(int) * 2;
        if (write_exact(cfd, &resp, resp_hdr + (size_t)resp.length) < 0) break;
    }
    close(cfd);
}

int main(void) {
    signal(SIGCHLD, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);

    unlink(SOCK_PATH);

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) { LOGE("socket: %s", strerror(errno)); return 1; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCK_PATH, sizeof(addr.sun_path) - 1);

    if (bind(sfd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("bind: %s", strerror(errno)); close(sfd); return 1;
    }
    chmod(SOCK_PATH, 0777);

    if (listen(sfd, BACKLOG) < 0) {
        LOGE("listen: %s", strerror(errno)); close(sfd); return 1;
    }

    LOGI("rootd listening on %s", SOCK_PATH);

    while (1) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) { LOGE("accept: %s", strerror(errno)); continue; }
        pid_t child = fork();
        if (child == 0) {
            close(sfd);
            handle_client(cfd);
            exit(0);
        }
        close(cfd);
    }
    return 0;
}
