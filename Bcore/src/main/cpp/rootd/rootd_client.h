#pragma once
#include <stdint.h>
#include <stdbool.h>

#define ROOTD_SOCK_PATH "/dev/rootd.sock"
#define ROOTD_MAX_DATA  65536

#define ROOTD_CMD_READ_MEM  1
#define ROOTD_CMD_WRITE_MEM 2
#define ROOTD_CMD_EXEC_CMD  3
#define ROOTD_CMD_PING      4

#define ROOTD_STATUS_OK    0
#define ROOTD_STATUS_ERROR 1

#ifdef __cplusplus
extern "C" {
#endif

int  rootd_connect(void);
bool rootd_ping(int fd);
int  rootd_read_mem(int fd, int pid, long offset, int length, char* out_buf);
int  rootd_write_mem(int fd, int pid, long offset, int length, const char* buf);
int  rootd_exec_cmd(int fd, const char* cmd, char* out_buf);

#ifdef __cplusplus
}
#endif
