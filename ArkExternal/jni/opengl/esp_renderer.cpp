#include "esp_renderer.h"
#include "shaders.h"
#include <android/log.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <time.h>
#include <cmath>
#include <cstdio>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "ArkRender", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ArkRender", __VA_ARGS__)

// ── Colour helpers ────────────────────────────────────────────────────────────
static void hexToRgb(const char* hex, float& r, float& g, float& b) {
    unsigned int iv = 0;
    if (hex[0] == '#') hex++;
    sscanf(hex, "%06x", &iv);
    r = ((iv >> 16) & 0xFF) / 255.f;
    g = ((iv >>  8) & 0xFF) / 255.f;
    b = ( iv        & 0xFF) / 255.f;
}

static float lerp(float a, float b, float t) { return a + (b - a) * t; }

// ── SHM ───────────────────────────────────────────────────────────────────────
bool EspRenderer::openShm() {
    for (int i = 0; SHM_PATHS[i]; i++) {
        int fd = open(SHM_PATHS[i], O_RDONLY);
        if (fd < 0) continue;
        void* p = mmap(nullptr, ARK_SHM_SIZE, PROT_READ, MAP_SHARED, fd, 0);
        if (p == MAP_FAILED) { close(fd); continue; }
        _shmFd  = fd;
        _shmPtr = p;
        LOGI("SHM opened: %s", SHM_PATHS[i]);
        return true;
    }
    LOGE("SHM: all paths failed");
    return false;
}

// ── EGL ───────────────────────────────────────────────────────────────────────
bool EspRenderer::initEgl() {
    _dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (_dpy == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); return false; }

    EGLint maj, min;
    if (!eglInitialize(_dpy, &maj, &min)) { LOGE("eglInitialize failed"); return false; }

    EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig cfg; EGLint n = 0;
    if (!eglChooseConfig(_dpy, attribs, &cfg, 1, &n) || n == 0) {
        LOGE("eglChooseConfig failed"); return false;
    }

    EGLint ctxAttr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    _ctx = eglCreateContext(_dpy, cfg, EGL_NO_CONTEXT, ctxAttr);
    if (_ctx == EGL_NO_CONTEXT) { LOGE("eglCreateContext failed"); return false; }

    _surf = eglCreateWindowSurface(_dpy, cfg, _win, nullptr);
    if (_surf == EGL_NO_SURFACE) { LOGE("eglCreateWindowSurface failed"); return false; }

    if (!eglMakeCurrent(_dpy, _surf, _surf, _ctx)) {
        LOGE("eglMakeCurrent failed"); return false;
    }

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DEPTH_TEST);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    LOGI("EGL init OK %dx%d", _w, _h);
    return true;
}

GLuint EspRenderer::compileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512]; glGetShaderInfoLog(sh, 512, nullptr, buf);
        LOGE("Shader compile: %s", buf);
        glDeleteShader(sh); return 0;
    }
    return sh;
}

GLuint EspRenderer::linkProgram(GLuint vs, GLuint fs) {
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs); glAttachShader(prog, fs);
    glLinkProgram(prog);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[512]; glGetProgramInfoLog(prog, 512, nullptr, buf);
        LOGE("Program link: %s", buf);
        glDeleteProgram(prog); return 0;
    }
    return prog;
}

bool EspRenderer::initShaders() {
    GLuint vc = compileShader(GL_VERTEX_SHADER,   VERT_COLOR_SRC);
    GLuint fc = compileShader(GL_FRAGMENT_SHADER, FRAG_COLOR_SRC);
    GLuint vt = compileShader(GL_VERTEX_SHADER,   VERT_TEX_SRC);
    GLuint ft = compileShader(GL_FRAGMENT_SHADER, FRAG_TEX_SRC);
    if (!vc || !fc || !vt || !ft) return false;
    _colorProg = linkProgram(vc, fc);
    _texProg   = linkProgram(vt, ft);
    glDeleteShader(vc); glDeleteShader(fc);
    glDeleteShader(vt); glDeleteShader(ft);
    return _colorProg != 0 && _texProg != 0;
}

// ── Public API ────────────────────────────────────────────────────────────────
bool EspRenderer::init(ANativeWindow* win, int w, int h) {
    _win = win; _w = w; _h = h;
    return true; // EGL is init'd inside render thread for thread affinity
}

void EspRenderer::setFontAtlas(const uint8_t* rgba, int aw, int ah,
                                const float* cw, const float* co,
                                float ch, float atw) {
    _fontPending = { rgba, aw, ah, cw, co, ch, atw, true };
}

void EspRenderer::setFlags(bool line, bool box, bool skel, bool hp,
                            bool name, bool dist, bool count, bool veh) {
    _fLine  = line;  _fBox   = box;  _fSkel = skel;
    _fHp    = hp;    _fName  = name; _fDist = dist;
    _fCount = count; _fVeh   = veh;
}

void EspRenderer::startRenderThread() {
    _running = true;
    _thread = std::thread(&EspRenderer::renderLoop, this);
}

void EspRenderer::stopRenderThread() {
    _running = false;
    if (_thread.joinable()) _thread.join();
}

void EspRenderer::destroy() {
    stopRenderThread();
    if (_shmPtr && _shmPtr != MAP_FAILED) {
        munmap(_shmPtr, ARK_SHM_SIZE); _shmPtr = nullptr;
    }
    if (_shmFd >= 0) { close(_shmFd); _shmFd = -1; }
    if (_dpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (_surf != EGL_NO_SURFACE) eglDestroySurface(_dpy, _surf);
        if (_ctx  != EGL_NO_CONTEXT) eglDestroyContext(_dpy, _ctx);
        eglTerminate(_dpy);
        _dpy = EGL_NO_DISPLAY; _surf = EGL_NO_SURFACE; _ctx = EGL_NO_CONTEXT;
    }
    if (_win) { ANativeWindow_release(_win); _win = nullptr; }
}

// ── Render loop ───────────────────────────────────────────────────────────────
void EspRenderer::renderLoop() {
    if (!initEgl()) { LOGE("EGL init failed, render thread exiting"); return; }
    if (!initShaders()) { LOGE("Shader init failed"); return; }
    _batch.init();
    openShm();

    if (_fontPending.ready) {
        _font.upload(_fontPending.rgba, _fontPending.aw, _fontPending.ah,
                     _fontPending.cw, _fontPending.co,
                     _fontPending.ch, _fontPending.atw);
        _fontPending.ready = false;
    }

    struct timespec ts{ 0, 16666666L }; // ~60fps

    while (_running) {
        nanosleep(&ts, nullptr);

        // Retry SHM open if not yet available
        if (!_shmPtr) { openShm(); }

        EspFrame frame{};
        if (_shmPtr) {
            const volatile EspFrameHeader* hdr =
                reinterpret_cast<const volatile EspFrameHeader*>(_shmPtr);
            // Seqlock read
            uint32_t s1, s2;
            int tries = 0;
            do {
                s1 = hdr->seqWrite;
                if (s1 & 1u) { tries++; if(tries>100) break; continue; }
                memcpy(&frame, (void*)_shmPtr, sizeof(EspFrame));
                s2 = hdr->seqWrite;
            } while (s1 != s2);
        }

        glViewport(0, 0, _w, _h);
        glClear(GL_COLOR_BUFFER_BIT);
        _batch.reset();

        bool anyEnabled = _fLine || _fBox || _fSkel || _fHp ||
                          _fName || _fDist || _fCount || _fVeh;
        if (_shmPtr && anyEnabled) {
            drawFrame(frame);
        }

        _batch.flush(_colorProg, _texProg, (float)_w, (float)_h);
        eglSwapBuffers(_dpy, _surf);
    }

    LOGI("Render loop exited");
}

// ── Draw ESP frame ────────────────────────────────────────────────────────────
void EspRenderer::drawFrame(const EspFrame& frame) {
    float sw = (float)_w, sh = (float)_h;

    int ec = frame.hdr.enemyCount;
    int bc = frame.hdr.botCount;
    if (ec < 0 || ec > 100) ec = 0;
    if (bc < 0 || bc > 100) bc = 0;

    // Counter panel FIRST so panelBottomY is set before player loop
    _panelBottomY = 0.f;
    if (_fCount) {
        _panelBottomY = drawCountPanel(ec, bc, sw, sh);
    }

    int pc = frame.hdr.playerCount;
    if (pc < 0 || pc > 100) pc = 0;

    for (int i = 0; i < pc; i++) {
        drawPlayer(frame.players[i], sw, sh);
    }

    // Vehicles
    if (_fVeh) {
        int vc = frame.hdr.vehCount;
        if (vc < 0 || vc > 20) vc = 0;
        for (int i = 0; i < vc; i++) {
            const EspVehicle& v = frame.vehicles[i];
            float vx = v.x * sw, vy = v.y * sh;
            float r, g, b;
            if (v.hasEnemy) { r=1.f; g=0.31f; b=0.31f; }
            else            { r=0.3f; g=0.87f; b=0.42f; }
            char buf[64];
            snprintf(buf, sizeof(buf), "%s (%.0fm)",
                     v.name[0] ? v.name : "Vehicle", v.dist);
            if (_font.ready()) {
                _font.drawTextCentered(_batch, buf, vx, vy, 1.f, r, g, b, 0.9f);
            }
        }
    }

    // HUD: FPS top-left
    if (frame.fps > 0 && _font.ready()) {
        char buf[32]; snprintf(buf, sizeof(buf), "FPS:%d", frame.fps);
        _font.drawText(_batch, buf, 160.f, 36.f, 0.9f, 0.f, 1.f, 1.f, 0.9f);
    }

    // Watermark bottom-right
    if (_font.ready()) {
        const char* wm = "ARK CHEAT BGMI";
        float tw = _font.measureText(wm, 1.1f);
        _font.drawText(_batch, wm, sw - tw - 20.f, sh - 32.f,
                       1.1f, 1.f, 1.f, 1.f, 0.85f);
    }
}

// ── Draw single player ────────────────────────────────────────────────────────
static const int BONE_LINKS[][2] = {
    {0,1},{1,2},{2,3},{3,4},{4,5},
    {1,6},{6,7},{7,8},
    {1,9},{9,10},{10,11},
    {5,12},{12,13},{13,14},
    {5,15},{15,16}
};
static const int NUM_BONE_LINKS = 17;

void EspRenderer::drawPlayer(const EspPlayer& p, float sw, float sh) {
    float headX = p.headX * sw;
    float headY = p.headY * sh;
    float rootX = p.rootX * sw;
    float rootY = p.rootY * sh;

    // Proportional box
    float rawH  = rootY - headY;
    float boxH  = rawH > 16.f ? rawH : 16.f;
    float boxW  = boxH * 0.52f;
    float boxCX = (headX + rootX) * 0.5f;
    float bL    = boxCX - boxW * 0.5f;
    float bR    = boxCX + boxW * 0.5f;
    float bT    = headY - 2.f;
    float bB    = (rawH >= 16.f) ? (rootY + 2.f) : (bT + boxH + 4.f);

    // Box color
    float br, bg, bb;
    if      (p.isKnk) hexToRgb("#FF3333", br, bg, bb);
    else if (p.isBot)  hexToRgb(p.isVis ? "#FFC96B" : "#999999", br, bg, bb);
    else               hexToRgb(p.isVis ? "#A4FF73" : "#FF4444", br, bg, bb);

    // 1. ESP line: panel-bottom-center → enemy head
    if (_fLine) {
        float la = p.isVis ? 0.51f : 0.31f;
        float lr = p.isVis ? br : 0.6f;
        float lg = p.isVis ? bg : 0.6f;
        float lb = p.isVis ? bb : 0.6f;
        _batch.line(sw * 0.5f, _panelBottomY, boxCX, bT, lr, lg, lb, la);
    }

    // 2. Skeleton
    if (_fSkel) {
        for (int k = 0; k < NUM_BONE_LINKS; k++) {
            int f = BONE_LINKS[k][0], t = BONE_LINKS[k][1];
            if (f >= 17 || t >= 17) continue;
            if (!p.boneVis[f] || !p.boneVis[t]) continue;
            float fx = p.boneX[f]*sw, fy = p.boneY[f]*sh;
            float tx = p.boneX[t]*sw, ty = p.boneY[t]*sh;
            _batch.line(fx, fy, tx, ty, br, bg, bb, 0.78f);
        }
    }

    // 3. Glow + corner box
    if (_fBox) {
        _batch.glowBox(bL, bT, bR, bB, br, bg, bb);
    }

    // 4. Health bar — gradient above box
    if (_fHp) {
        float hp = p.health < 0.f ? 0.f : (p.health > 1.f ? 1.f : p.health);
        float hbT = bT - 5.f, hbB = bT - 2.f;
        // Background
        _batch.fillRect(bL, hbT, bR, hbB, 0.f, 0.f, 0.f, 0.6f);
        // Gradient: green→yellow→red based on hp
        float hr, hg, hb;
        if (hp > 0.5f) {
            float t = (hp - 0.5f) / 0.5f; // 1=full→0=50%
            hr = lerp(1.f, 0.f, t);
            hg = 1.f; hb = 0.f;
        } else {
            float t = hp / 0.5f; // 1=50%→0=empty
            hr = 1.f;
            hg = lerp(0.f, 1.f, t); hb = 0.f;
        }
        _batch.fillRect(bL, hbT, bL + boxW * hp, hbB, hr, hg, hb, 1.f);
    }

    // 5. Name tag
    if (_fName && _font.ready()) {
        const char* dname = (p.name[0] != '\0') ? p.name : (p.isBot ? "Bot" : "?");
        float tw = _font.measureText(dname, 1.f);
        float nY = bB + 2.f;
        // Dark background pill
        _batch.fillRect(boxCX - tw*0.5f - 3.f, nY,
                        boxCX + tw*0.5f + 3.f, nY + 16.f,
                        0.f, 0.f, 0.f, 0.55f);
        _font.drawTextCentered(_batch, dname, boxCX, nY, 1.f,
                               1.f, 1.f, 1.f, 0.95f);
    }

    // 6. Distance
    if (_fDist && _font.ready()) {
        float dy = bB + (_fName ? 20.f : 2.f);
        char dbuf[16]; snprintf(dbuf, sizeof(dbuf), "%.0fm", p.distance);
        _font.drawTextCentered(_batch, dbuf, boxCX, dy, 0.85f,
                               br, bg, bb, 0.9f);
    }
}

// ── Enemy count panel (center-top) ───────────────────────────────────────────
float EspRenderer::drawCountPanel(int ec, int bc, float sw, float /*sh*/) {
    if (ec <= 0 && bc <= 0) return 0.f;

    const float TW     = 90.f;
    const float ROW_H  = 18.f;
    const float PAD    = 7.f;
    const float HDR_H  = ROW_H;
    int   rows = ec + bc;
    float PH   = PAD + HDR_H + rows * ROW_H + PAD;
    float px   = sw / 2.f - TW / 2.f;
    float py   = 8.f;

    // Background
    _batch.fillRect(px, py, px + TW, py + PH, 0.f, 0.f, 0.f, 0.7f);
    // Border (corner box style)
    _batch.cornerBox(px, py, px+TW, py+PH, 1.f, 1.f, 1.f, 0.3f, 1.f);

    if (_font.ready()) {
        // Header
        _font.drawTextCentered(_batch, "ENEMIES",
            px + TW*0.5f, py + PAD, 0.75f,
            0.78f, 0.82f, 0.86f, 0.82f);
        // Divider line
        float divY = py + PAD + HDR_H;
        _batch.line(px+4, divY, px+TW-4, divY, 1.f, 1.f, 1.f, 0.2f);

        float ry = divY + 2.f;
        for (int i = 0; i < ec; i++) {
            char buf[12]; snprintf(buf, sizeof(buf), "PLR %d", i+1);
            _font.drawTextCentered(_batch, buf,
                px + TW*0.5f, ry, 0.8f, 0.643f, 1.f, 0.451f, 0.9f);
            ry += ROW_H;
        }
        for (int i = 0; i < bc; i++) {
            char buf[12]; snprintf(buf, sizeof(buf), "BOT %d", i+1);
            _font.drawTextCentered(_batch, buf,
                px + TW*0.5f, ry, 0.8f, 1.f, 0.788f, 0.42f, 0.9f);
            ry += ROW_H;
        }
    }

    return py + PH; // ESP line origin Y
}
