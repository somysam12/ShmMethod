#pragma once
#include "StreamESP.h"
#include <stdint.h>

// ── Wire protocol ─────────────────────────────────────────────────────────────
#define ESP_MAGIC          0x56455350u   // "VESP"
#define ESP_UDP_PORT       28901         // ESP frame data  (VNative→VApp)
#define ESP_EVENT_PORT     28902         // Events          (VNative→VApp)
#define ESP_MAX_PLAYERS    64
#define ESP_MAX_VEHICLES   32

// EspPlayer / EspVehicle are the same structs as StreamPlayer / StreamVehicle
// (packed, so Java ByteBuffer reads them with zero ambiguity)
typedef StreamPlayer  EspPlayer;
typedef StreamVehicle EspVehicle;

#pragma pack(push, 1)

struct EspFrame {
    uint32_t magic;                     // ESP_MAGIC
    uint32_t seq;                       // incremented every send
    int32_t  screenW, screenH;          // game GL surface dimensions
    int32_t  playerCount;
    int32_t  vehicleCount;
    int32_t  enemyCount, botCount;
    uint8_t  showLine, showSkel, showHealth, showName;
    uint8_t  showDist, showVehicle, showCount, showWeapon;
    float    aimRange;
    char     watermark[64];
    char     expiry[64];
    EspPlayer  players[ESP_MAX_PLAYERS];
    EspVehicle vehicles[ESP_MAX_VEHICLES];
};
// sizeof(EspFrame) = 172 + 64*240 + 32*48 = 17068 bytes

#pragma pack(pop)

// ── API ───────────────────────────────────────────────────────────────────────
void EspSocket_Init();
void EspSocket_SendFrame(
    EspPlayer*  players,  int playerCount,
    EspVehicle* vehicles, int vehicleCount,
    int screenW, int screenH,
    bool showLine, bool showSkel, bool showHealth,
    bool showName, bool showDist, bool showVehicle,
    bool showCount, bool showWeapon,
    float aimRange,
    int enemyCount, int botCount,
    const char* watermark, const char* expiry);
void EspSocket_SendEvent(const char* event);
void EspSocket_Shutdown();
