#pragma once
#include <stdint.h>
#include <android/native_activity.h>

// ── Bone index mapping (matches RenderESP collection order) ──────────────────
// 0=Head  1=neck_01  2=spine_03  3=spine_02  4=spine_01  5=pelvis
// 6=upperarm_r  7=lowerarm_r  8=hand_r
// 9=upperarm_l  10=lowerarm_l  11=hand_l
// 12=thigh_r  13=calf_r  14=foot_r
// 15=thigh_l  16=calf_l  17=foot_l
#define STREAM_BONE_COUNT 18

#pragma pack(push, 1)

struct StreamPlayer {
    float   headX, headY;
    float   footX, footY;
    float   health, healthMax;
    float   distance;
    float   bones[STREAM_BONE_COUNT][2];
    char    name[32];
    char    weaponName[32];
    uint8_t hasLOS;
    uint8_t isBot;
    uint8_t isKnocked;
    uint8_t pad;
};

struct StreamVehicle {
    float   scrX, scrY;
    float   distance;
    uint8_t hasDriver;
    uint8_t pad[3];
    char    name[32];
};

#pragma pack(pop)

// ── Globals (defined in EspSocket.cpp) ───────────────────────────────────────
extern bool g_StreamMode;
extern bool s_streamReady;

// ── API ───────────────────────────────────────────────────────────────────────
bool StreamESP_Init(ANativeActivity* activity);
void StreamESP_DrawFrame(
    StreamPlayer*  players,  int playerCount,
    StreamVehicle* vehicles, int vehicleCount,
    int screenW, int screenH,
    bool showLine, bool showSkel, bool showHealth,
    bool showName, bool showDist,
    int enemyCount, int botCount,
    const char* watermark, const char* expiry,
    bool grenadeWarn);
void StreamESP_ClearOnce();
