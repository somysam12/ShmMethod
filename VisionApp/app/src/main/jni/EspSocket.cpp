// EspSocket.cpp — UDP sender: VNative → VApp overlay (127.0.0.1)
//
// ESP data frames  → port 28901  (one datagram per game frame, ~17 KB)
// Event strings    → port 28902  (small text: "loginsuccess", etc.)
//
// Non-blocking sendto — if the kernel buffer is full the frame is dropped;
// VApp always renders the most recently received frame so a single drop is
// invisible at 60 fps.

#include "EspSocket.h"
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <android/log.h>

#define ESPTAG "EspSocket"
#define ESPLOGI(...) __android_log_print(ANDROID_LOG_INFO,  ESPTAG, __VA_ARGS__)
#define ESPLOGE(...) __android_log_print(ANDROID_LOG_ERROR, ESPTAG, __VA_ARGS__)

// ── shared globals (StreamESP.h declares them extern) ────────────────────────
bool g_StreamMode  = false;
bool s_streamReady = false;

// ── socket state ─────────────────────────────────────────────────────────────
static int               s_fd = -1;
static struct sockaddr_in s_espDst{};
static struct sockaddr_in s_evtDst{};
static EspFrame          s_frame{};

// ── StreamESP shims (main.cpp calls these via StreamESP.h) ───────────────────
bool StreamESP_Init(ANativeActivity* /*activity*/) {
    EspSocket_Init();
    s_streamReady = true;
    return true;
}

void StreamESP_DrawFrame(
    StreamPlayer*  players,  int playerCount,
    StreamVehicle* vehicles, int vehicleCount,
    int screenW, int screenH,
    bool showLine, bool showSkel, bool showHealth,
    bool showName, bool showDist,
    int enemyCount, int botCount,
    const char* watermark, const char* expiry,
    bool /*grenadeWarn*/)
{
    EspSocket_SendFrame(
        players, playerCount, vehicles, vehicleCount,
        screenW, screenH,
        showLine, showSkel, showHealth, showName, showDist,
        true, true, true,
        150.f, enemyCount, botCount, watermark, expiry);
}

void StreamESP_ClearOnce() {
    if (s_fd < 0) return;
    memset(&s_frame, 0, sizeof(s_frame));
    s_frame.magic = ESP_MAGIC;
    s_frame.seq++;
    sendto(s_fd, &s_frame, sizeof(EspFrame), MSG_DONTWAIT,
           reinterpret_cast<sockaddr*>(&s_espDst), sizeof(s_espDst));
}

// ── EspSocket API ─────────────────────────────────────────────────────────────

void EspSocket_Init() {
    if (s_fd >= 0) return;  // already init

    s_fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s_fd < 0) { ESPLOGE("socket() failed"); return; }

    // Non-blocking so sendto never stalls the game thread
    int fl = fcntl(s_fd, F_GETFL, 0);
    fcntl(s_fd, F_SETFL, fl | O_NONBLOCK);

    // Enlarge send buffer to hold ~2 frames
    int sndbuf = 65536;
    setsockopt(s_fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));

    // ESP frame destination
    memset(&s_espDst, 0, sizeof(s_espDst));
    s_espDst.sin_family      = AF_INET;
    s_espDst.sin_port        = htons(ESP_UDP_PORT);
    s_espDst.sin_addr.s_addr = inet_addr("127.0.0.1");

    // Event destination
    memset(&s_evtDst, 0, sizeof(s_evtDst));
    s_evtDst.sin_family      = AF_INET;
    s_evtDst.sin_port        = htons(ESP_EVENT_PORT);
    s_evtDst.sin_addr.s_addr = inet_addr("127.0.0.1");

    ESPLOGI("UDP sockets ready → esp:28901  events:28902");
}

void EspSocket_SendFrame(
    EspPlayer*  players,  int playerCount,
    EspVehicle* vehicles, int vehicleCount,
    int screenW, int screenH,
    bool showLine, bool showSkel, bool showHealth,
    bool showName, bool showDist, bool showVehicle,
    bool showCount, bool showWeapon,
    float aimRange,
    int enemyCount, int botCount,
    const char* watermark, const char* expiry)
{
    if (s_fd < 0) return;

    s_frame.magic        = ESP_MAGIC;
    s_frame.seq++;
    s_frame.screenW      = screenW;
    s_frame.screenH      = screenH;
    s_frame.playerCount  = (playerCount  < ESP_MAX_PLAYERS  ? playerCount  : ESP_MAX_PLAYERS);
    s_frame.vehicleCount = (vehicleCount < ESP_MAX_VEHICLES ? vehicleCount : ESP_MAX_VEHICLES);
    s_frame.enemyCount   = enemyCount;
    s_frame.botCount     = botCount;
    s_frame.showLine     = showLine;
    s_frame.showSkel     = showSkel;
    s_frame.showHealth   = showHealth;
    s_frame.showName     = showName;
    s_frame.showDist     = showDist;
    s_frame.showVehicle  = showVehicle;
    s_frame.showCount    = showCount;
    s_frame.showWeapon   = showWeapon;
    s_frame.aimRange     = aimRange;

    if (watermark) { strncpy(s_frame.watermark, watermark, 63); s_frame.watermark[63] = 0; }
    else s_frame.watermark[0] = 0;
    if (expiry)    { strncpy(s_frame.expiry, expiry, 63); s_frame.expiry[63] = 0; }
    else s_frame.expiry[0] = 0;

    if (players && s_frame.playerCount > 0)
        memcpy(s_frame.players, players, s_frame.playerCount * sizeof(EspPlayer));
    if (vehicles && s_frame.vehicleCount > 0)
        memcpy(s_frame.vehicles, vehicles, s_frame.vehicleCount * sizeof(EspVehicle));

    sendto(s_fd, &s_frame, sizeof(EspFrame), MSG_DONTWAIT,
           reinterpret_cast<sockaddr*>(&s_espDst), sizeof(s_espDst));
}

void EspSocket_SendEvent(const char* event) {
    if (s_fd < 0 || !event) return;
    sendto(s_fd, event, strlen(event), MSG_DONTWAIT,
           reinterpret_cast<sockaddr*>(&s_evtDst), sizeof(s_evtDst));
}

void EspSocket_Shutdown() {
    if (s_fd >= 0) { close(s_fd); s_fd = -1; }
    s_streamReady = false;
}
