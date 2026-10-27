#pragma once
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>
#include <atomic>
#include <thread>
#include <cstdint>
#include <cstring>
#include "gl_batch.h"
#include "font_atlas.h"

// ── SHM layout (mirrors Java EspOverlay constants exactly) ───────────────────
// MAX_PLAYERS=100, MAX_VEHICLES=20, MAX_BONES=17
// PLAYER_STRIDE=216, VEHICLE_STRIDE=48, FRAME_HEADER=32

#pragma pack(push, 1)
struct EspBone {
    float x, y;           // normalized 0..1
};

struct EspPlayer {
    float   headX, headY; // normalized 0..1
    float   rootX, rootY;
    float   boneX[17];
    float   boneY[17];
    uint8_t boneVis[17];
    uint8_t _pad[3];      // struct alignment padding after boneVis[17]
    float   health;       // 0..1
    float   distance;     // metres
    uint8_t isBot;
    uint8_t isVis;
    uint8_t isKnk;
    uint8_t hasArmor;
    char    name[32];
};

struct EspVehicle {
    float   x, y;         // normalized 0..1
    float   dist;
    uint8_t hasEnemy;
    uint8_t _pad[3];
    char    name[32];
};

struct EspFrameHeader {
    uint32_t seqWrite;
    uint32_t _reserved1;
    float    _reserved2;
    float    _reserved3;
    int32_t  playerCount;
    int32_t  vehCount;
    int32_t  enemyCount;
    int32_t  botCount;
};

struct EspFrame {
    EspFrameHeader hdr;
    EspPlayer  players[100];
    EspVehicle vehicles[20];
    int32_t    fps;
    char       expiry[48];
};
#pragma pack(pop)

static_assert(sizeof(EspPlayer)  == 216, "EspPlayer size mismatch");
static_assert(sizeof(EspVehicle) == 48,  "EspVehicle size mismatch");

#define ARK_SHM_SIZE (sizeof(EspFrame) + 64)

static const char* SHM_PATHS[] = {
    "/storage/emulated/0/CrashLog/ark_esp_v1",
    "/data/local/tmp/ark_esp_v1",
    nullptr
};

// ── Renderer ──────────────────────────────────────────────────────────────────
class EspRenderer {
public:
    bool init(ANativeWindow* win, int w, int h);
    void setFontAtlas(const uint8_t* rgba, int aw, int ah,
                      const float* cw, const float* co,
                      float ch, float atw);
    void setFlags(bool line, bool box, bool skel, bool hp,
                  bool name, bool dist, bool count, bool veh);
    void startRenderThread();
    void stopRenderThread();
    void destroy();
    void surfaceLost();
    bool surfaceRestored(ANativeWindow* newWin, int w, int h);

private:
    EGLDisplay   _dpy  = EGL_NO_DISPLAY;
    EGLSurface   _surf = EGL_NO_SURFACE;
    EGLContext   _ctx  = EGL_NO_CONTEXT;
    EGLConfig    _cfg  = nullptr;
    ANativeWindow* _win = nullptr;
    int          _w = 0, _h = 0;
    GLuint       _colorProg = 0, _texProg = 0;

    // Surface lifecycle — written by main thread, processed by render thread only.
    // All actual EGL calls stay on the render thread to avoid EGL threading violations.
    std::atomic<bool>          _surfaceReady{false};
    std::atomic<bool>          _surfaceLostPending{false};
    std::atomic<ANativeWindow*> _pendingRestoreWin{nullptr};
    std::atomic<int>            _pendingRestoreW{0};
    std::atomic<int>            _pendingRestoreH{0};
    GlBatch      _batch;
    FontAtlas    _font;
    std::thread  _thread;
    std::atomic<bool> _running{false};

    std::atomic<bool> _fLine{false},  _fBox{false},   _fSkel{false},
                      _fHp{false},    _fName{false},   _fDist{false},
                      _fCount{false}, _fVeh{false};

    // Pending font atlas data (set before EGL init)
    struct FontPending {
        const uint8_t* rgba = nullptr;
        int aw = 0, ah = 0;
        const float* cw = nullptr;
        const float* co = nullptr;
        float ch = 0.f, atw = 0.f;
        bool ready = false;
    } _fontPending;

    int    _shmFd  = -1;
    void*  _shmPtr = nullptr;
    float  _panelBottomY = 0.f;  // set by drawCountPanel, used by drawPlayer

    bool   openShm();
    void   renderLoop();
    void   drawFrame(const EspFrame& frame);
    void   drawPlayer(const EspPlayer& p, float sw, float sh);
    float  drawCountPanel(int ec, int bc, float sw, float sh);
    GLuint compileShader(GLenum type, const char* src);
    GLuint linkProgram(GLuint vs, GLuint fs);
    bool   initEgl();
    bool   initShaders();
};
