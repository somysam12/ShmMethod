#include "rootd_client.h"

#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>

#define TAG "rootd_client"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#pragma pack(push, 1)
struct Request {
    int  cmd;
    int  pid;
    long offset;
    int  length;
    char data[ROOTD_MAX_DATA];
};

struct Response {
    int  status;
    int  length;
    char data[ROOTD_MAX_DATA];
};
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

int rootd_connect(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, ROOTD_SOCK_PATH, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("connect to rootd failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static int send_recv(int fd, struct Request* req, size_t payload_len, struct Response* resp) {
    size_t hdr = sizeof(int)*2 + sizeof(long) + sizeof(int);
    if (write_exact(fd, req, hdr + payload_len) < 0) return -1;

    size_t resp_hdr = sizeof(int) * 2;
    if (read_exact(fd, resp, resp_hdr) < 0) return -1;
    if (resp->length > 0 && resp->length <= ROOTD_MAX_DATA) {
        if (read_exact(fd, resp->data, (size_t)resp->length) < 0) return -1;
    }
    return 0;
}

bool rootd_ping(int fd) {
    struct Request req;
    struct Response resp;
    memset(&req,  0, sizeof(req));
    memset(&resp, 0, sizeof(resp));
    req.cmd = ROOTD_CMD_PING;
    if (send_recv(fd, &req, 0, &resp) < 0) return false;
    return resp.status == ROOTD_STATUS_OK;
}

int rootd_read_mem(int fd, int pid, long offset, int length, char* out_buf) {
    struct Request req;
    struct Response resp;
    memset(&req,  0, sizeof(req));
    memset(&resp, 0, sizeof(resp));
    req.cmd    = ROOTD_CMD_READ_MEM;
    req.pid    = pid;
    req.offset = offset;
    req.length = length;
    if (send_recv(fd, &req, 0, &resp) < 0) return -1;
    if (resp.status != ROOTD_STATUS_OK) return -1;
    if (resp.length > 0 && out_buf)
        memcpy(out_buf, resp.data, (size_t)resp.length);
    return resp.length;
}

int rootd_write_mem(int fd, int pid, long offset, int length, const char* buf) {
    struct Request req;
    struct Response resp;
    memset(&req,  0, sizeof(req));
    memset(&resp, 0, sizeof(resp));
    req.cmd    = ROOTD_CMD_WRITE_MEM;
    req.pid    = pid;
    req.offset = offset;
    req.length = length;
    if (length > ROOTD_MAX_DATA) length = ROOTD_MAX_DATA;
    memcpy(req.data, buf, (size_t)length);
    if (send_recv(fd, &req, (size_t)length, &resp) < 0) return -1;
    if (resp.status != ROOTD_STATUS_OK) return -1;
    return resp.length;
}

int rootd_exec_cmd(int fd, const char* cmd, char* out_buf) {
    struct Request req;
    struct Response resp;
    memset(&req,  0, sizeof(req));
    memset(&resp, 0, sizeof(resp));
    req.cmd = ROOTD_CMD_EXEC_CMD;
    int cmdlen = (int)strlen(cmd);
    if (cmdlen >= ROOTD_MAX_DATA) cmdlen = ROOTD_MAX_DATA - 1;
    memcpy(req.data, cmd, (size_t)cmdlen);
    req.length = cmdlen;
    if (send_recv(fd, &req, (size_t)cmdlen, &resp) < 0) return -1;
    if (resp.status != ROOTD_STATUS_OK) return -1;
    if (resp.length > 0 && out_buf)
        memcpy(out_buf, resp.data, (size_t)resp.length);
    return resp.length;
}