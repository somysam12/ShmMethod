#include "Helper/include.h"
#include "Helper/definition.h"
#include "Helper/bypass.h"
#include "Helper/KCHardSec.h"
#include "Helper/KCSecure.h"
#include "Helper/KCSecureHB.h"
#include "Helper/Login.h"
#include "Helper/KC_LuaInject.h"
#include "Helper/CustomSkinIDs.h"
#include "Helper/SKINS/skin.h"
#include "Helper/SKINS/GunSkin.cpp"
#include "Helper/SKINS/KillMsg.cpp"
#include "Helper/SKINS/DeadBoxSkin.cpp"
#include "Helper/KC_GunMaps.h"
#include "Helper/KC_SkinData.h"
#include "Helper/KC_SkinApply.h"
#include "Helper/KC_UserPrefs.h"
#include "StreamESP.h"
#include "EspFrameData.h"
#include "EspSharedMem.h"
//0
#include "imgui/imgui.h"
#include "imgui/backends/imgui_impl_android.h"
#define IMGUI_IMPL_OPENGL_ES3
#include "imgui/backends/imgui_impl_opengl3.h"
#include "Helper/KCVisionFont.h"
#include "Helper/KCHindiFont.h"
#include <cfloat>

static ImFont* g_VisionFont = nullptr;

// ── ESP text buffer — RenderESP fills, ImGui hook draws (Vision font) ──
struct KCEspText {
    float x, y;
    unsigned int color;
    bool centered;
    char text[64];
};
static std::vector<KCEspText> g_EspTexts;
static std::vector<KCEspText> g_EspTextsRender;
static volatile bool g_EspUseImGui = true;
static int  g_CntEnemies = 0, g_CntBots = 0;
static bool g_CntShow    = false;

static void KC_PushEspText(float x, float y, unsigned int color, bool centered, const char* txt) {
    if (g_EspTexts.size() > 256) return;
    KCEspText e;
    e.x = x; e.y = y; e.color = color; e.centered = centered;
    strncpy(e.text, txt, sizeof(e.text)-1);
    e.text[sizeof(e.text)-1] = 0;
    g_EspTexts.push_back(e);
}

// TCP feature server forward declarations
extern void StartTcpFeatureServer();
void TCP_SetStreamMode(bool on) {
    if (!on) StreamESP_ClearOnce();
    g_StreamMode = on;
}
// g_App, bValid, bconfiger, isLogin, EXP, g_Token, g_Auth declared in Login.h
bool g_SkinsEnabled = true;
json items_data;
using json = nlohmann::json;
std::map < int, bool > Items;
#define targetLibName OBFUSCATE("libanort.so")
FLinearColor RedColour(1.f, 0.f, 0.f, 1.f);
static int g_streamEnemyCount = 0;
static int g_streamBotCount   = 0;

// ══════════════════════════════════════════════════════════════
//  GL SCREENSHOT SYSTEM — Telegram chicken dinner auto-feedback
//  glReadPixels in PostRender (GL thread) → BMP → Telegram bot
// ══════════════════════════════════════════════════════════════
#include <GLES2/gl2.h>
#include <EGL/egl.h>
#include <atomic>

std::atomic<bool> g_RequestGLShot{false};
std::atomic<bool> g_GLShotReady{false};
char g_GLShotPath[512] = "/data/data/com.bgmi/tg_win.bmp";

static bool WriteBMP(const char* path, const uint8_t* rgba, int w, int h) {
    int rowStride = (w * 3 + 3) & ~3;
    int dataSize  = rowStride * h;
    int fileSize  = 14 + 40 + dataSize;
    FILE* f = fopen(path, "wb");
    if (!f) return false;
    uint8_t fhdr[14] = {
        'B','M',
        (uint8_t)(fileSize>>0),(uint8_t)(fileSize>>8),
        (uint8_t)(fileSize>>16),(uint8_t)(fileSize>>24),
        0,0,0,0, 54,0,0,0
    };
    fwrite(fhdr,1,14,f);
    uint8_t ihdr[40] = {};
    *(uint32_t*)(ihdr+0)=40; *(int32_t*)(ihdr+4)=w; *(int32_t*)(ihdr+8)=h;
    *(uint16_t*)(ihdr+12)=1; *(uint16_t*)(ihdr+14)=24;
    *(uint32_t*)(ihdr+16)=0; *(uint32_t*)(ihdr+20)=dataSize;
    fwrite(ihdr,1,40,f);
    std::vector<uint8_t> row(rowStride,0);
    for (int y=0;y<h;y++) {
        const uint8_t* src = rgba+(size_t)y*w*4;
        for (int x=0;x<w;x++) { row[x*3+0]=src[x*4+2]; row[x*3+1]=src[x*4+1]; row[x*3+2]=src[x*4+0]; }
        fwrite(row.data(),1,rowStride,f);
    }
    fclose(f);
    return true;
}

static void DoGLScreenshot() {
    int w=(int)screenWidth, h=(int)screenHeight;
    if (w<=0||h<=0) { g_GLShotReady=true; return; }

    // Bind FBO 0 = window backbuffer (fully composited frame, never black)
    GLint prevFBO=0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING,&prevFBO);
    glBindFramebuffer(GL_FRAMEBUFFER,0);
    glFinish();   // GPU flush — frame must be complete before readback

    std::vector<uint8_t> pixels((size_t)w*h*4);
    glPixelStorei(GL_PACK_ALIGNMENT,1);
    glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());

    glBindFramebuffer(GL_FRAMEBUFFER,prevFBO);

    // Priority: loader internal dir (always writable in loader process) → sdcard fallback
    const char* tries[] = {
        "/data/data/com.bgmi/tg_win.bmp",
        "/sdcard/Android/data/com.bgmi/files/tg_win.bmp",
        "/sdcard/tg_win.bmp",
        nullptr
    };
    for (int i=0;tries[i];i++) {
        if (WriteBMP(tries[i],pixels.data(),w,h)) {
            strncpy(g_GLShotPath,tries[i],sizeof(g_GLShotPath)-1);
            LOGI("GL screenshot saved → %s (%dx%d)",g_GLShotPath,w,h);
            g_GLShotReady=true;
            return;
        }
    }
    LOGI("GL screenshot: all paths failed");
    g_GLShotReady=true;
}

static volatile bool g_InMatchForVision = false;
static void KC_DrawImGuiWatermark();   // defined below

// Renderer hook for GL screenshot + ImGui watermark/expiry/ESP
#ifndef KC_RENDERER_OFFSET
#define KC_RENDERER_OFFSET 0xAF76604
#endif
__int64 (*orig_renderer)(__int64 *a, int b) = nullptr;
__int64 hook_renderer(__int64 *a, int b) {
    // ImGui watermark + expiry + ESP texts (Vision font)
    KC_DrawImGuiWatermark();

    if (g_RequestGLShot) {
        EGLDisplay dpy=eglGetCurrentDisplay(); EGLSurface sfc=eglGetCurrentSurface(EGL_DRAW);
        if (dpy!=EGL_NO_DISPLAY&&sfc!=EGL_NO_SURFACE) {
            EGLint sw=0,sh=0;
            eglQuerySurface(dpy,sfc,EGL_WIDTH,&sw);
            eglQuerySurface(dpy,sfc,EGL_HEIGHT,&sh);
            if (sw>0&&sh>0) { screenWidth=sw; screenHeight=sh; }
        }
        if (screenWidth>0&&screenHeight>0) {
            g_RequestGLShot=false;
            DoGLScreenshot();
        }
    }
    // HideESP: init hidden surface once
    static bool s_espInited = false;
    if (!s_espInited && g_App && g_App->activity) {
        s_espInited = StreamESP_Init(g_App->activity);
    }
    return orig_renderer(a,b);
}

// ── Expiry countdown string — shared by ImGui and HideESP ────────────────────
static void KC_BuildExpiryStr(char* out, int n) {
    if (!out || n <= 0) return;
    out[0] = 0;
    if (EXP.empty() || EXP == "NULL") return;
    if (EXP == "Lifetime") { snprintf(out, n, "Lifetime"); return; }
    if (g_KC_ExpUnix > 0) {
        double diff = difftime((time_t)g_KC_ExpUnix, time(nullptr));
        if (diff <= 0) { snprintf(out, n, "Expired"); return; }
        int days=(int)(diff/86400), hours=(int)(diff/3600);
        int minutes=(int)(diff/60), seconds=(int)diff;
        if      (days    >= 1) snprintf(out,n,"ACCESS VALIDITY %d DAY%s",    days,    days==1?"":"S");
        else if (hours   >= 1) snprintf(out,n,"ACCESS VALIDITY %d HOUR%s",   hours,   hours==1?"":"S");
        else if (minutes >= 1) snprintf(out,n,"ACCESS VALIDITY %d MINUTE%s", minutes, minutes==1?"":"S");
        else                   snprintf(out,n,"ACCESS VALIDITY %d SECOND%s", seconds, seconds==1?"":"S");
    } else {
        snprintf(out, n, "Active");
    }
}

// ── FString (UTF-16) → UTF-8 for ImGui ───────────────────────────────────────
struct FStringRaw { unsigned short* Data; int32_t Count; int32_t Max; };
static std::string KC_FStringToUTF8(const FString& fs) {
    std::string out;
    const FStringRaw* raw = reinterpret_cast<const FStringRaw*>(&fs);
    if (!raw->Data || raw->Count <= 0) return out;
    for (int i = 0; i < raw->Count && i < 63; i++) {
        unsigned int c = (unsigned int)raw->Data[i];
        if (c == 0) break;
        if      (c < 0x80)  { out += (char)c; }
        else if (c < 0x800) { out += (char)(0xC0|(c>>6)); out += (char)(0x80|(c&0x3F)); }
        else                { out += (char)(0xE0|(c>>12)); out += (char)(0x80|((c>>6)&0x3F)); out += (char)(0x80|(c&0x3F)); }
    }
    return out;
}

// ── ImGui watermark + expiry + ESP texts (Vision font) ───────────────────────
static bool s_KCImGuiInit  = false;
// File-scope fps so ShmESP_Write can read it
static int  g_ShmFpsDisplay = 0;
static int  g_ShmFpsCounter = 0;
static float g_ShmFpsLast  = 0.f;

static void KC_DrawImGuiWatermark() {
    if (g_StreamMode) return;
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLSurface sfc = eglGetCurrentSurface(EGL_DRAW);
    if (dpy == EGL_NO_DISPLAY || sfc == EGL_NO_SURFACE) return;
    EGLint sw = 0, sh = 0;
    eglQuerySurface(dpy, sfc, EGL_WIDTH, &sw);
    eglQuerySurface(dpy, sfc, EGL_HEIGHT, &sh);
    if (sw <= 0 || sh <= 0) return;
    glWidth = sw; glHeight = sh;

    if (!s_KCImGuiInit) {
        ImGui::CreateContext();
        ImGui::StyleColorsDark();
        ImGui_ImplAndroid_Init();
        ImGui_ImplOpenGL3_Init("#version 300 es");
        ImGuiIO& io = ImGui::GetIO();
        io.IniFilename = NULL;
        float fsz = (float)glHeight * 0.024f;
        if (fsz < 15.0f) fsz = 15.0f;
        if (fsz > 30.0f) fsz = 30.0f;

        ImFontConfig cfg; cfg.FontDataOwnedByAtlas = false;
        static const ImWchar latin_ranges[] = { 0x0020,0x00FF, 0x2000,0x206F, 0x2190,0x21FF, 0x2600,0x26FF, 0 };
        g_VisionFont = io.Fonts->AddFontFromMemoryTTF(
            (void*)KC_VISION_FONT_DATA, (int)KC_VISION_FONT_SIZE, fsz, &cfg, latin_ranges);

        ImFontConfig cfg2; cfg2.MergeMode = true; cfg2.FontDataOwnedByAtlas = false;
        static const ImWchar hindi_ranges[] = { 0x0900,0x097F, 0xA8E0,0xA8FF, 0 };
        io.Fonts->AddFontFromMemoryTTF(
            (void*)KC_HINDI_FONT_DATA, (int)KC_HINDI_FONT_SIZE, fsz, &cfg2, hindi_ranges);
        io.Fonts->Build();
        io.Fonts->AddFontDefault();
        s_KCImGuiInit = true;
    }

    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame(glWidth, glHeight);
    ImGui::NewFrame();

    ImDrawList* dl = ImGui::GetForegroundDrawList();
    ImFont* vf = g_VisionFont ? g_VisionFont : ImGui::GetFont();
    float vfSize = vf->FontSize;
    float wmSize = vfSize * 1.25f;

    // Watermark + expiry — skip when SHM overlay is active (overlay draws them)
    if (!g_StreamMode && !g_ShmReady) {
        // "ARK" = yellow, " CHEAT" = orange, " BGMI" = white — no overlap, side by side
        const char* wm1 = "ARK";
        const char* wm2 = " CHEAT";
        const char* wm3 = " BGMI";
        ImVec2 ts1 = vf->CalcTextSizeA(wmSize, FLT_MAX, 0.0f, wm1);
        ImVec2 ts2 = vf->CalcTextSizeA(wmSize, FLT_MAX, 0.0f, wm2);
        ImVec2 ts3 = vf->CalcTextSizeA(wmSize, FLT_MAX, 0.0f, wm3);
        float totalW = ts1.x + ts2.x + ts3.x;
        float wY = (float)glHeight - ts1.y - 14.0f;
        float wX = (float)glWidth  - totalW - 60.0f;
        // Shadows
        dl->AddText(vf, wmSize, ImVec2(wX+1,           wY+1), IM_COL32(0,0,0,255), wm1);
        dl->AddText(vf, wmSize, ImVec2(wX+ts1.x+1,     wY+1), IM_COL32(0,0,0,255), wm2);
        dl->AddText(vf, wmSize, ImVec2(wX+ts1.x+ts2.x+1,wY+1),IM_COL32(0,0,0,255), wm3);
        // Coloured text
        dl->AddText(vf, wmSize, ImVec2(wX,              wY),  IM_COL32(255,220,0,255),  wm1); // YELLOW
        dl->AddText(vf, wmSize, ImVec2(wX+ts1.x,        wY),  IM_COL32(255,120,0,255),  wm2); // ORANGE
        dl->AddText(vf, wmSize, ImVec2(wX+ts1.x+ts2.x,  wY),  IM_COL32(255,255,255,255),wm3); // WHITE

        char expStr[64] = {0};
        KC_BuildExpiryStr(expStr, sizeof(expStr));
        if (expStr[0]) {
            ImVec2 ets = vf->CalcTextSizeA(vfSize, FLT_MAX, 0.0f, expStr);
            float eX = 60.0f;
            float eY = (float)glHeight - ets.y - 14.0f;
            dl->AddText(vf, vfSize, ImVec2(eX+1,eY+1), IM_COL32(0,0,0,255),   expStr);
            dl->AddText(vf, vfSize, ImVec2(eX,  eY),   IM_COL32(255,0,0,255), expStr);
        }
    }

    // ESP texts — skip when SHM overlay draws them
    if (!g_StreamMode && !g_ShmReady) {
        float espSize = vfSize * 0.85f;
        for (size_t i = 0; i < g_EspTextsRender.size(); i++) {
            const KCEspText& e = g_EspTextsRender[i];
            float x = e.x, y = e.y;
            if (e.centered) {
                ImVec2 ts2 = vf->CalcTextSizeA(espSize, FLT_MAX, 0.0f, e.text);
                x -= ts2.x * 0.5f;
            }
            dl->AddText(vf, espSize, ImVec2(x+1,y+1), IM_COL32(0,0,0,220), e.text);
            dl->AddText(vf, espSize, ImVec2(x,  y),   e.color,             e.text);
        }
    }

    // Counter pill — rounded red box, white 2-digit text, cy=62
    if (!g_StreamMode && g_CntShow) {
        char cbuf[16];
        snprintf(cbuf, sizeof(cbuf), "%02d - %02d", g_CntEnemies, g_CntBots);
        float cs = vfSize;
        ImVec2 ts = vf->CalcTextSizeA(cs, FLT_MAX, 0.0f, cbuf);
        float cx = (float)glWidth * 0.5f, cy = 62.f;
        float padX = 18.f, padY = 5.f;
        float bw = ts.x + padX * 2.f, bh = ts.y + padY * 2.f;
        ImVec2 pmin(cx - bw * 0.5f, cy - bh * 0.5f), pmax(cx + bw * 0.5f, cy + bh * 0.5f);
        float rounding = bh * 0.5f;
        dl->AddRectFilled(pmin, pmax, IM_COL32(255,0,0,255), rounding);
        float tx = cx - ts.x * 0.5f, ty = pmin.y + padY;
        dl->AddText(vf, cs, ImVec2(tx+1,ty+1), IM_COL32(0,0,0,200),     cbuf);
        dl->AddText(vf, cs, ImVec2(tx,  ty),   IM_COL32(255,255,255,255), cbuf);
    }

    // ── FPS tracking (always update file-scope var for SHM) ─────────────────
    {
        float nowSec = (float)(ImGui::GetTime());
        g_ShmFpsCounter++;
        if (nowSec - g_ShmFpsLast >= 1.0f) {
            g_ShmFpsDisplay = g_ShmFpsCounter;
            g_ShmFpsCounter = 0;
            g_ShmFpsLast    = nowSec;
        }
        // Native FPS draw: only when SHM overlay is NOT active
        if (!g_ShmReady) {
            char fpsBuf[24];
            snprintf(fpsBuf, sizeof(fpsBuf), "FPS: %d", g_ShmFpsDisplay);
            float fpsSize = vfSize * 0.95f;
            float fX = 80.f, fY = 36.f;
            dl->AddText(vf, fpsSize, ImVec2(fX+1,fY+1), IM_COL32(0,0,0,200),     fpsBuf);
            dl->AddText(vf, fpsSize, ImVec2(fX,  fY),   IM_COL32(0,255,255,255),  fpsBuf);
        }
    }

    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

void OnChickenDinnerDetected(); // defined in TelegramIntegration.cpp
static pthread_mutex_t g_LogMutex = PTHREAD_MUTEX_INITIALIZER;

void LogToFile(const char* fmt, ...)
{
    pthread_mutex_lock(&g_LogMutex);

    FILE* fp = fopen("/sdcard/logs.txt", "a");

    if(fp)
    {
        time_t t = time(nullptr);
        tm* tmInfo = localtime(&t);

        char timeBuf[32] = {};

        if(tmInfo)
            strftime(
                timeBuf,
                sizeof(timeBuf),
                "%Y-%m-%d %H:%M:%S",
                tmInfo
            );

        fprintf(fp, "[%s] ", timeBuf);

        va_list args;
        va_start(args, fmt);
        vfprintf(fp, fmt, args);
        va_end(args);

        fprintf(fp, "\n");

        fflush(fp);
        fclose(fp);
    }

    pthread_mutex_unlock(&g_LogMutex);
}


#include <mutex>
#include <unordered_map>
#include <string>
#include <cstdarg>
#include <cstdio>
#include <ctime>




void LogUniqueToFile(const char* format, ...)
{
    std::lock_guard<std::mutex> lock(logMutex);

    char buffer[1024] = {};

    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);

    std::string logMessage = buffer;

    // Already logged? Skip it.
    if (loggedEntries.find(logMessage) != loggedEntries.end())
        return;

    loggedEntries[logMessage] = true;

    FILE* fp = fopen("/sdcard/unique_logs.txt", "a");

    if (fp)
    {
        time_t t = time(nullptr);
        tm* tmInfo = localtime(&t);

        char timeBuf[32] = {};

        if (tmInfo)
        {
            strftime(
                timeBuf,
                sizeof(timeBuf),
                "%Y-%m-%d %H:%M:%S",
                tmInfo
            );
        }

        fprintf(fp, "[%s] %s\n", timeBuf, logMessage.c_str());

        fflush(fp);
        fclose(fp);
    }
}

#define LOGU(...) LogUniqueToFile(__VA_ARGS__)


float GetD3DDistance(FVector a, FVector b)
{
    FVector vector = {a.X - b.X, a.Y - b.Y, a.Z - b.Z};
    return sqrt(((vector.X * vector.X) + (vector.Y * vector.Y)) + (vector.Z * vector.Z));
}

template <typename T>
void WriteMemory(uintptr_t addr, const T* values, size_t numBytes)
{
    Tools::WriteAddr(reinterpret_cast<void*>(addr), const_cast<void*>(reinterpret_cast<const void*>(values)), numBytes);
}

auto writeBytes = [](uintptr_t address, std::initializer_list<uint8_t> values)
    {
        WriteMemory(address, values.begin(), values.size());
    };
    

void logUnique(int priority, const char* tag, const char* format, ...) {
    std::lock_guard<std::mutex> lock(logMutex); // Lock the mutex before accessing loggedEntries

    va_list args;
    va_start(args, format);

    char buffer[1024]; // Adjust size as needed
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);

    std::string logMessage = buffer;

    //Check if log message is already present in the map
    auto it = loggedEntries.find(logMessage);

    if (it == loggedEntries.end()) {
        loggedEntries[logMessage] = true;
        __android_log_print(priority, "TERROR", "%s", logMessage.c_str());
    }
}




// ==================Backtrace Code===============//

#include <unwind.h>        // For stack unwinding (_Unwind_Backtrace, _Unwind_Reason_Code, etc.)
#include <dlfcn.h>         // For dynamic linking (dladdr, Dl_info)
#include <stdio.h>         // For sprintf, printf
#include <stdlib.h>        // For malloc, free
#include <string.h>        // For strlen, strstr
#include <vector>          // For std::vector
#include <string>          // For std::string
#include <libgen.h>        // For basename

uintptr_t basechut(const char *name)
{
    uintptr_t base = 0;
    char line[512];

    FILE *f = fopen("/proc/self/maps", "r");

    if (!f)
    {
        return 0;
    }

    while (fgets(line, sizeof line, f))
    {
        uintptr_t tmpBase;
        char tmpName[256];
        if (sscanf(line, "%" PRIXPTR "-%*" PRIXPTR " %*s %*s %*s %*s %s", &tmpBase, tmpName) > 0)
        {
            if (!strcmp(basename(tmpName), name))
            {
                base = tmpBase;
                break;
            }
        }
    }

    fclose(f);
    return base;
}


std::string getLibraryName(void *address)
{
    std::vector<std::string> libraries = {"libUE4.so", "libTDataMaster.so", "libgcloud.so",
                                          "libanogs.so", "libanort.so", "libCrashSight.so",
                                          "libapp.so", "libav1d.so", "libav1d_jni.so",
                                          "libavif_android.so", "libc++_shared.so",
                                          "libc++_shared.so", "libcubehawk.so", "libflutter.so",
                                          "libgamemaster.so", "libgcloudarch.so",
                                          "libgcloudcore.so", "libGCloudVoice.so",
                                          "libgnustl_shared.so", "libGvoiceNN.so",
                                          "libijkffmpeg.so", "libINTLCompliance.so",
                                          "libINTLFoundation.so", "libITOP.so", "libkk-image.so",
                                          "libmarsxlog.so", "libmmkv.so", "libnpps-jni.so",
                                          "libopenplatform.so", "libPandoraVideo.so",
                                          "libPixUI_PXPlugin.so", "libpixuiCurl.so",
                                          "libPxExtFFi.so", "libsentry.so", "libsentry-android.so",
                                          "libspecialgem.so", "libswappy.so", "libtgpa.so",
                                          "libtool-checker.so", "libvlink.so"};
    for (const auto &lib : libraries)
    {
        uintptr_t baseAddr = basechut(lib.c_str());
        if (baseAddr != 0)
        {
            Dl_info info;
            if (dladdr(address, &info))
            {
                if (std::string(info.dli_fname).find(lib) != std::string::npos)
                {
                    return lib;
                }
            }
        }
    }
    return "";
}

struct BacktraceState
{
    void **current;
    void **end;
};

static _Unwind_Reason_Code unwindCallback(struct _Unwind_Context *context, void *arg)
{
    auto *state = static_cast<BacktraceState *>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc)
    {
        if (state->current == state->end)
        {
            return _URC_END_OF_STACK;
        }
        else
        {
            *state->current++ = reinterpret_cast<void *>(pc);
        }
    }
    return _URC_NO_REASON;
}

void print_backtrace_logs(const char *a1)
{
    const size_t max = 100;
    void *buffer[max];
    BacktraceState state = {buffer, buffer + max};
    _Unwind_Backtrace(unwindCallback, &state);
    int count = state.current - buffer;
    for (int idx = 0; idx < count; idx++)
    {
        const void *addr = buffer[idx];
        Dl_info info;
        if (dladdr(addr, &info) && info.dli_fname)
        {
            if (strstr(info.dli_fname, "marsxlog") == nullptr &&
                strstr(info.dli_fname, "libc.so") == nullptr &&
                strstr(info.dli_fname, "libgnustl_shared.so") == nullptr)
            {
            }
        }
    }
    int line_number = 1;
    for (int idx = count - 1; idx >= 0; idx--)
    {
        const void *addr = buffer[idx];
        Dl_info info;
        if (dladdr(addr, &info) && info.dli_fname)
        {
            if (strstr(info.dli_fname, "marsxlog") == nullptr &&
                strstr(info.dli_fname, "libc.so") == nullptr &&
                strstr(info.dli_fname, "libgnustl_shared.so") == nullptr)
            {
                uintptr_t offset = (uintptr_t)addr - (uintptr_t)info.dli_fbase;
                LOGU("Function %s called from -> %d stage -> %s : %lx\n", a1,
                     line_number++, basename(info.dli_fname), offset);
            }
        }
    }
}

int __fastcall (*Unwind_VRS_Pop)(unsigned int *a1, int a2, unsigned __int64 a3);

int __fastcall hUnwind_VRS_Pop(unsigned int *a1, int a2, unsigned __int64 a3)
{
    return 0;
}

int __fastcall (*_gnu_unwind_execute)(unsigned int *a1, int a2);

int __fastcall h_gnu_unwind_execute(unsigned int *a1, int a2)
{
    int v7 = 0; // r4
    return v7;
}

void print_backtrace(const char *value)
{
    const size_t max = 100;
    void *buffer[max];
    BacktraceState state = {buffer, buffer + max};
    _Unwind_Backtrace(unwindCallback, &state);

    int count = state.current - buffer;

    size_t total_length = strlen(value);
    for (int idx = count - 1; idx >= 0; idx--)
    {
        const void *addr = buffer[idx];
        int line_number = 1;
        Dl_info info;
        if (dladdr(addr, &info) && info.dli_fname)
        {
            uintptr_t offset = (uintptr_t)addr - (uintptr_t)info.dli_fbase;

            std::string offsetop = std::to_string(offset);
            std::string library(basename(info.dli_fname));
            std::string x(value);
            std::string total = x + " >> " + library + " + " + offsetop;
            total_length += strlen(total.c_str()) * line_number++;
        }
    }

    char *backtraceInfo = (char *)malloc(total_length + 1);

    sprintf(backtraceInfo, "%s", value);

    for (int idx = count - 1; idx >= 0; idx--)
    {
        const void *addr = buffer[idx];
        Dl_info info;
        if (dladdr(addr, &info) && info.dli_fname)
        {
            uintptr_t offset = (uintptr_t)addr - (uintptr_t)info.dli_fbase;

            if (strstr(basename(info.dli_fname), "libc.so") == nullptr && strstr(basename(info.dli_fname), "libpubgm.so") == nullptr)
            {

                if (strstr(basename(info.dli_fname), "libUE4.so"))
                {
                    LOGE("%s >> %s + %p ", value, basename(info.dli_fname), offset);
                }
                else
                {
                    sprintf(backtraceInfo + strlen(backtraceInfo), " >> %s + %p", basename(info.dli_fname), offset);
                }
            }
        }
    }
    LOGE("%s", backtraceInfo);
    free(backtraceInfo);
}



//====================Backtrace Code ============//

void GetLocalPlayer()
{
    auto GWorld = GetWorld();
    if (!GWorld)
        { Cheat::localPlayer = nullptr; Cheat::localController = nullptr; return; }

    auto NetDriver = GWorld->NetDriver;
    if (!NetDriver)
        { Cheat::localPlayer = nullptr; Cheat::localController = nullptr; return; }

    auto ServerConnection = NetDriver->ServerConnection;
    if (!ServerConnection)
        { Cheat::localPlayer = nullptr; Cheat::localController = nullptr; return; }

    auto PlayerController = ServerConnection->PlayerController;
    if (!PlayerController)
        { Cheat::localPlayer = nullptr; Cheat::localController = nullptr; return; }

    Cheat::localController = (ASTExtraPlayerController *)PlayerController;

    if (GWorld->PersistentLevel)
    {
        auto Actors = GetActors();
        ASTExtraPlayerCharacter *localPlayer = nullptr;

        for (int i = 0; i < Actors.size(); i++)
        {
            auto Actor = Actors[i];
            if (isObjectInvalid(Actor))
                continue;

            if (Actor->IsA(ASTExtraPlayerCharacter::StaticClass()))
            {
                if (((ASTExtraPlayerCharacter *)Actor)->PlayerKey == ((ASTExtraPlayerController *)PlayerController)->PlayerKey)
                {
                    localPlayer = (ASTExtraPlayerCharacter *)Actor;
                    break;
                }
            }
        }

        Cheat::localPlayer = localPlayer;  // always update (nullptr on match-end)
    }
}
int U_FLOAT(long int addr, float value){
    Tools::WriteAddr((void*)(addr), (void*)&value , 4);
    return 0;
}





// getClipboardText() — defined in Helper/Login.h


// ════════════════════════════════════════════════════════════════
//  LOGIN SYSTEM — reads ONLY from loader's lic file
//  Loader (fopsdual) writes: key\nserial\npkg
//  Path: /storage/emulated/0/Android/aRkCheat.lic
//  Native never creates/writes lic — loader owns that file.
// ════════════════════════════════════════════════════════════════

std::string Filepath = std::string(LIC_DIR) + LIC_FILE_NAME;

bool fileExists(const std::string &filePath) {
    std::ifstream file(filePath); return file.good();
}
bool isFileEmpty(const std::string &filePath) {
    std::ifstream file(filePath);
    return file.peek() == std::ifstream::traits_type::eof();
}

static char keyForLogin[128]    = {};
static char serialFromLic[256]  = {};
static char pkgFromLic[256]     = {};

// Parse lic: line1=key  line2=serial(UUID)  line3=pkg
// Written by loader to /storage/emulated/0/Android/ArkCheat.lic
void GetKey() {
    memset(keyForLogin,   0, sizeof(keyForLogin));
    memset(serialFromLic, 0, sizeof(serialFromLic));
    memset(pkgFromLic,    0, sizeof(pkgFromLic));

    FILE* f = fopen(Filepath.c_str(), "r");
    if (!f) return;
    char buf[512];
    int lineNum = 0;
    while (fgets(buf, sizeof(buf), f) && lineNum < 3) {
        std::string line(buf);
        while (!line.empty() && (line.back()=='\n'||line.back()=='\r'||line.back()==' ')) line.pop_back();
        if (!line.empty()) {
            if      (lineNum == 0) strncpy(keyForLogin,   line.c_str(), sizeof(keyForLogin)-1);
            else if (lineNum == 1) strncpy(serialFromLic, line.c_str(), sizeof(serialFromLic)-1);
            else if (lineNum == 2) strncpy(pkgFromLic,    line.c_str(), sizeof(pkgFromLic)-1);
            lineNum++;
        }
    }
    fclose(f);
}

void logError(const char *errorMessage) {
    int fd = open("/storage/emulated/0/Android/aRkCheat_error.txt", O_WRONLY|O_CREAT|O_TRUNC, 0666);
    if (fd!=-1) { write(fd, errorMessage, strlen(errorMessage)); write(fd, "\n", 1); close(fd); }
}

// Forward declarations — defined later in main.cpp
using MessageBoxExtType = int (*)(unsigned int, const char16_t*, const char16_t*, unsigned int);
extern MessageBoxExtType MessageBoxExt;
extern std::u16string StringToU16(const std::string&);

static void* _crashTimerThread(void*) { sleep(5); exit(1); return nullptr; }

static void ShowDialogAndCrash(const std::string& title, const std::string& msg) {
    pthread_t crashTid; pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&crashTid, &attr, _crashTimerThread, nullptr);
    pthread_attr_destroy(&attr);
    if (MessageBoxExt) {
        std::u16string v1 = StringToU16(msg);
        std::u16string v2 = StringToU16(title);
        MessageBoxExt(0, v1.c_str(), v2.c_str(), 0);
    }
    exit(1);
}

static int ClassifyStatus(const std::string& s) {
    if (s == "OK")          return 0;
    if (s == "MAINTENANCE") return 1;
    if (s == "EXPIRED")     return 2;
    if (s == "MAX_DEVICE")  return 3;
    if (s == "INVALID")     return 4;
    if (s == "CLONE")       return 5;
    if (s == "BLOCKED")     return 6;
    return 7;
}

void *loda_thread(void *) {
    while (!g_App) { sleep(1); }

    if (!fileExists(Filepath) || isFileEmpty(Filepath)) {
        ShowDialogAndCrash("Login Required",
            "Please login in the aRkCheat app first.\nLicense file not found.");
        return nullptr;
    }

    GetKey();

    if (keyForLogin[0] == '\0') {
        ShowDialogAndCrash("Invalid License",
            "License file is corrupted.\nRelogin in the aRkCheat app.");
        return nullptr;
    }

    std::string Keystatus;

    // Set Gamepackage from lic file so Login/LoginWithSerial never sees nullptr
    static char s_pkgBuf[256] = "com.bgmi";
    if (pkgFromLic[0] != '\0')
        strncpy(s_pkgBuf, pkgFromLic, sizeof(s_pkgBuf)-1);
    Gamepackage = s_pkgBuf;

    // Use loader's pre-computed UUID to avoid Android 8+ per-app ID mismatch
    if (serialFromLic[0] != '\0')
        Keystatus = LoginWithSerial(keyForLogin, serialFromLic);
    else
        Keystatus = Login(keyForLogin);

    int cls = ClassifyStatus(Keystatus);

    if (cls == 0) {
        isLogin = bconfiger && g_Auth == g_Token;
        bValid  = isLogin;
        if (!isLogin) {
            ShowDialogAndCrash("Login Failed",
                "Auth verification failed.\nPlease contact support.");
            return nullptr;
        }
        if (MessageBoxExt) {
            std::u16string v1 = StringToU16("Login Successful");
            std::u16string v2 = StringToU16("aRkCheat");
            MessageBoxExt(0, v1.c_str(), v2.c_str(), 0);
        }
        // ── Skin selection dialog (shown only after bValid success) ──────────
        if (MessageBoxExt) {
            int skinSel = MessageBoxExt(1,
                (const char16_t*)StringToU16("Yes = Enable All Skins\nNo  = Disable All Skins").c_str(),
                (const char16_t*)StringToU16("Do You Want To Use Skins?").c_str(), 0);
            if (skinSel == 1) {
                GunSkin = true;          // user said YES — enable gun skins now
            } else {
                g_SkinsEnabled = false;
                GunSkin = false;
                KC_LuaInjected = true;   // skip wardrobe Lua injection
            }
        }
        return nullptr;
    } else if (cls == 1) {
        ShowDialogAndCrash("Server Maintenance","Server is under maintenance.\nPlease try again later.");
    } else if (cls == 2) {
        ShowDialogAndCrash("Key Expired","Your key has expired.\nPlease purchase a new key.");
    } else if (cls == 3) {
        ShowDialogAndCrash("Device Limit Reached","This key is already activated on max devices.\nContact support to reset.");
    } else if (cls == 4) {
        ShowDialogAndCrash("Invalid Key","Key not found. Relogin in the aRkCheat app.");
    } else if (cls == 5) {
        ShowDialogAndCrash("Clone Detected","This key is bound to a different APK.\nDo not use cloned APKs.");
    } else if (cls == 6) {
        ShowDialogAndCrash("Key Blocked","This key has been blocked.\nContact support.");
    } else {
        ShowDialogAndCrash("Connection Failed","Could not reach server.\nCheck your internet and try again.");
    }
    return nullptr;
}


void RenderESP(UCanvas* Canvas)
{
    screenWidth = Canvas->SizeX;
    screenHeight = Canvas->SizeY;

    // ── SHM init (once) ───────────────────────────────────────────────────
    if (!g_ShmReady) ShmESP_Init();

    // ── ESP frame buffer for this render tick ─────────────────────────────
    // All player/vehicle data collected here, written to SHM at end.
    // External EspOverlay.java reads SHM and draws on Android Canvas.
    ArkEspFrame shmFrame;
    memset(&shmFrame, 0, sizeof(shmFrame));
    shmFrame.canvasW = (float)screenWidth;
    shmFrame.canvasH = (float)screenHeight;

    if(Canvas) {
        g_InMatchForVision = true;  // signal ImGui hook to draw watermark/expiry
        // SHM mode: internal g_EspTexts still cleared (ImGui watermark/expiry
        // still draws via ImGui hook — only player ESP goes to SHM now)
        if (g_EspUseImGui && !g_StreamMode) g_EspTexts.clear();

        // ── Chicken Dinner → Telegram screenshot ─────────────────
        try {
            auto GWorld = GetWorld();
            static bool bChickenFired = false;
            if (!GWorld || !GWorld->GameState) {
                bChickenFired = false;
            } else {
                auto* gs = reinterpret_cast<ASTExtraGameStateBase*>(GWorld->GameState);
                if ((int)gs->AliveTeamNum == 1 && !bChickenFired) {
                    bChickenFired = true;
                    OnChickenDinnerDetected();
                }
            }
        } catch (...) {}

        // ── HideESP: auto-init EGL surface when stream mode enabled ──────────
        if (g_StreamMode && !s_streamReady && g_App && g_App->activity)
            StreamESP_Init(g_App->activity);

        // OFF-transition: clear hidden surface once when HideESP turned off
        {
            static bool s_prevStream = false;
            if (s_prevStream && !g_StreamMode && s_streamReady)
                StreamESP_ClearOnce();
            s_prevStream = g_StreamMode;
        }

        bool s_streamDone = false;
        // If stream mode ON but surface not ready, clear texts and fall through to canvas path
        if (g_StreamMode && !s_streamReady) {
            if (g_EspUseImGui) g_EspTexts.clear(); // safe to clear — canvas path will repopulate
        }
        if (g_StreamMode && s_streamReady && Cheat::localPlayer && Cheat::localController) {
            static StreamPlayer  s_splayers[64];
            static StreamVehicle s_svehicles[16];
            int s_scount = 0, s_vcount = 0;
            int enemyCount = 0, botCount = 0;

            auto Actors = GetActors();
            for (auto* Actor : Actors) {
                if (!Actor || (uintptr_t)Actor < 0x100000) continue;

                // Vehicle
                if (Actor->IsA(ASTExtraVehicleBase::StaticClass()) && s_vcount < 16) {
                    auto* Veh = (ASTExtraVehicleBase*)Actor;
                    float vDist = Cheat::localPlayer->GetDistanceTo(Veh) / 100.f;
                    if (vDist < 500.f) {
                        FVector2D vSC;
                        if (W2S(Veh->K2_GetActorLocation(), &vSC)) {
                            StreamVehicle& sv = s_svehicles[s_vcount++];
                            sv.scrX = vSC.X; sv.scrY = vSC.Y;
                            sv.distance = vDist;
                            sv.hasDriver = false;
                            if (Veh->VehicleSeats) {
                                auto& Occ = Veh->VehicleSeats->SeatOccupiers;
                                for (int s = 0; s < Occ.Num(); s++) {
                                    if (Occ[s] && Occ[s] != Cheat::localPlayer)
                                        sv.hasDriver = true;
                                }
                            }
                            strncpy(sv.name, GetVehicleName(Veh), 31); sv.name[31] = 0;
                        }
                    }
                    continue;
                }

                // Player
                if (!Actor->IsA(ASTExtraPlayerCharacter::StaticClass())) continue;
                auto* ph = (ASTExtraPlayerCharacter*)Actor;
                if (ph->PlayerKey == Cheat::localController->PlayerKey) continue;
                if (ph->TeamID    == Cheat::localController->TeamID)    continue;
                if (ph->bDead || ph->bHidden) continue;
                float distH = Cheat::localPlayer->GetDistanceTo(ph) / 100.f;
                if (distH > 600.f) continue;

                FVector headW = ph->GetBonePos("Head", {});
                FVector rootW = ph->GetBonePos("Root", {});
                FVector2D headSC, rootSC;
                bool hOk = W2S(headW, &headSC);
                bool rOk = W2S(rootW, &rootSC);
                if (!hOk && !rOk) continue;
                if (!hOk) { headSC = rootSC; headSC.Y -= 60.f; }
                if (!rOk) { rootSC.X = headSC.X; rootSC.Y = headSC.Y + 180.f; }

                if (s_scount >= 64) break;
                StreamPlayer& sp = s_splayers[s_scount++];
                sp.headX = headSC.X; sp.headY = headSC.Y;
                sp.footX = rootSC.X; sp.footY = rootSC.Y;
                sp.health    = ph->Health;
                sp.healthMax = ph->HealthMax > 0.f ? ph->HealthMax : 100.f;
                sp.distance  = distH;
                sp.isBot     = (ph->bEnsure != 0);
                sp.isKnocked = (!ph->bDead && ph->Health <= 0.f);
                sp.hasLOS    = Cheat::localController->LineOfSightTo(
                                   Cheat::localController->PlayerCameraManager,
                                   ph->GetBonePos("Head", {}), true);
                if (sp.isBot) { strncpy(sp.name, "Bot", 31); botCount++; }
                else {
                    const char* pn = ph->PlayerName.ToString();
                    strncpy(sp.name, pn ? pn : "Player", 31);
                    enemyCount++;
                }
                sp.name[31] = 0;

                static const char* bnames[] = {
                    "Head","neck_01","spine_03","spine_02",
                    "spine_01","pelvis","clavicle_r","lowerarm_r","hand_r",
                    "clavicle_l","lowerarm_l","hand_l","thigh_r","calf_r",
                    "foot_r","thigh_l"
                };
                for (int b = 0; b < 16; b++) {
                    FVector2D bs;
                    FVector bp = ph->GetBonePos(bnames[b], {});
                    if (W2S(bp, &bs)) {
                        sp.bones[b][0] = bs.X; sp.bones[b][1] = bs.Y;
                        sp.boneValid[b] = true;
                    } else {
                        sp.bones[b][0] = 0.f; sp.bones[b][1] = 0.f;
                        sp.boneValid[b] = false;
                    }
                }
            }

            g_streamEnemyCount = enemyCount;
            g_streamBotCount   = botCount;

            char s_expStr[64] = {0};
            KC_BuildExpiryStr(s_expStr, sizeof(s_expStr));

            StreamESP_DrawFrame(
                s_splayers, s_scount,
                s_svehicles, s_vcount,
                (int)Canvas->SizeX, (int)Canvas->SizeY,
                Cheat::Esp::Line, Cheat::Esp::Skeleton,
                Cheat::Esp::Health, Cheat::Esp::Name, Cheat::Esp::Distance,
                enemyCount, botCount,
                "ARK CHEAT BGMI", s_expStr[0] ? s_expStr : nullptr
            );
            s_streamDone = true;
            // NOTE: Stream-path early ShmESP_Write removed intentionally.
            // The stream path doesn't fill boneX/Y/Valid or isVisible in shmFrame,
            // so writing here would give the Java overlay stale/incomplete data
            // (bones=0 → skeleton at origin, isVisible always false → wrong colors).
            // The main PostRender loop below writes a COMPLETE frame with all fields.
            // Java overlay always reads the complete main-loop frame — correct positions.
        }

        if (!s_streamDone && Cheat::localPlayer && Cheat::localController)
        {
            int totalEnemies = 0, totalBots = 0;

            auto Actors = GetActors();

            for (auto &Actor : Actors)
            {
                if (Actor->IsA(ASTExtraPlayerCharacter::StaticClass()))
                {
                    auto Player = (ASTExtraPlayerCharacter *) Actor;
                    bool IsVisible = Cheat::localController->LineOfSightTo(Cheat::localController->PlayerCameraManager, Player->GetBonePos("Head", {}), true);
                    bool IsBotVisible = Player->bEnsure && IsVisible;
                    bool IsPlayerVisible = !Player->bEnsure && IsVisible;
                    bool IsBotNotVisible = Player->bEnsure && !IsVisible;
                    bool IsPlayerNotVisible = !Player->bEnsure && !IsVisible;

                    FLinearColor Color;
                    if (IsBotVisible || IsPlayerVisible)
                    {
                        Color = FLinearColor(0.f, 1.f, 0.f, 1.f); // Visible = Green
                    }
                    else
                    {
                        Color = FLinearColor(1.f, 0.f, 0.f, 1.f); // In cover = Red
                    }

                    float Distance = Cheat::localPlayer->GetDistanceTo(Player) / 100.0f;

                    if (Player->PlayerKey == Cheat::localController->PlayerKey)
                        continue;

                    if (Player->TeamID == Cheat::localController->TeamID)
                        continue;

                    if (Player->bDead)
                        continue;

                    if (Player->bHidden)
                        continue;

                    if (Player->bEnsure)
                        totalBots++;
                    else
                        totalEnemies++;

                    if (Player)
                    {
                        auto HeadPos = Player->GetBonePos("Head", {});
                        FVector2D headPosSC;
                        auto RootPos = Player->GetBonePos("Root", {});
                        FVector2D RootPosSC;

                        if (W2S(HeadPos, (FVector2D *) & headPosSC) &&
                                W2S(RootPos, (FVector2D *) & RootPosSC))
                        {
                            // ── SHM: collect player data (no internal Canvas drawing) ──
                            if (shmFrame.playerCount < ARK_MAX_PLAYERS) {
                                ArkEspPlayer& ep = shmFrame.players[shmFrame.playerCount++];
                                // Normalize to 0.0-1.0 — works on ANY device resolution
                                ep.headX    = headPosSC.X / (float)screenWidth;
                                ep.headY    = headPosSC.Y / (float)screenHeight;
                                ep.rootX    = RootPosSC.X / (float)screenWidth;
                                ep.rootY    = RootPosSC.Y / (float)screenHeight;
                                ep.health   = Player->HealthMax > 0.f
                                            ? (Player->Health / Player->HealthMax) : 0.f;
                                ep.distance = Distance;
                                ep.isBot     = (uint8_t)(Player->bEnsure != 0);
                                ep.isVisible = (uint8_t)IsVisible;
                                ep.isKnocked = (uint8_t)(!Player->bDead && Player->Health <= 0.f);
                                ep.hasArmor  = 0; // HasArmor not in SDK — unused
                                if (Player->bEnsure)
                                    strncpy(ep.name, "Bot", 31);
                                else {
                                    const char* pn = KC_FStringToUTF8(Player->PlayerName).c_str();
                                    strncpy(ep.name, pn ? pn : "Player", 31);
                                }
                                ep.name[31] = 0;

                                // Skeleton bones — normalized
                                static const char* boneNames[] = {
                                    "Head","neck_01","spine_03","spine_02",
                                    "spine_01","pelvis","upperarm_r","lowerarm_r","hand_r",
                                    "upperarm_l","lowerarm_l","hand_l",
                                    "thigh_r","calf_r","foot_r","thigh_l","calf_l"
                                };
                                for (int b = 0; b < ARK_MAX_BONES; b++) {
                                    FVector2D bs;
                                    FVector bp = Player->GetBonePos(boneNames[b], {});
                                    if (W2S(bp, &bs)) {
                                        ep.boneX[b] = bs.X / (float)screenWidth;
                                        ep.boneY[b] = bs.Y / (float)screenHeight;
                                        ep.boneValid[b] = 1;
                                    }
                                }
                            }

                            // ── Internal drawing DISABLED — external overlay draws ──
                            // Line, Name, Distance, Skeleton, Health all go via SHM
                            if (false) // disabled block kept for reference
                            {
                            if (Cheat::Esp::Name || Cheat::Esp::Distance)
                            {
                                if (Cheat::Esp::Name) {
                                    if (g_EspUseImGui) {
                                        if (!Player->bEnsure)
                                            KC_PushEspText(RootPosSC.X, RootPosSC.Y + 18.0f,
                                                IM_COL32(255,255,255,255), true,
                                                KC_FStringToUTF8(Player->PlayerName).c_str());
                                        else
                                            KC_PushEspText(RootPosSC.X, RootPosSC.Y + 18.0f,
                                                IM_COL32(255,255,255,255), true, "Bot");
                                    } else {
                                        tslFont->LegacyFontSize = 13;
                                        if (!Player->bEnsure)
                                            DrawOutlinedText(Canvas, Player->PlayerName,
                                                FVector2D{RootPosSC.X, RootPosSC.Y + 18.0f},
                                                FLinearColor(1.f,1.f,1.f,1.f), COLOR_BLACK, true);
                                        else
                                            DrawOutlinedText(Canvas, FString("Bot"),
                                                FVector2D{RootPosSC.X, RootPosSC.Y + 18.0f},
                                                FLinearColor(1.f,1.f,1.f,1.f), COLOR_BLACK, true);
                                        tslFont->LegacyFontSize = TSL_FONT_DEFAULT_SIZE;
                                    }
                                }
                                if (Cheat::Esp::Distance) {
                                    std::string MyStdString = std::to_string((int)Distance) + "m";
                                    float aimRange = (Cheat::BulletTrack::BulletRange > 0.f) ? Cheat::BulletTrack::BulletRange : Cheat::Aimbot::Radius;
                                    if (g_EspUseImGui) {
                                        unsigned int dcol = (Distance <= aimRange) ? IM_COL32(255,255,0,255) : IM_COL32(255,0,0,255);
                                        KC_PushEspText(RootPosSC.X, RootPosSC.Y + 40.0f, dcol, true, MyStdString.c_str());
                                    } else {
                                        tslFont->LegacyFontSize = 13;
                                        FLinearColor distColor = (Distance <= aimRange) ? FLinearColor(1.f,1.f,1.f,1.f) : FLinearColor(1.f,0.f,0.f,1.f);
                                        DrawOutlinedText(Canvas, FString(MyStdString.c_str()),
                                            FVector2D{RootPosSC.X, RootPosSC.Y + 40.0f}, distColor, COLOR_BLACK, true);
                                        tslFont->LegacyFontSize = TSL_FONT_DEFAULT_SIZE;
                                    }
                                }
                            }

                            } // end disabled block
                            if (false && Cheat::Esp::Skeleton) // skeleton via SHM now
                            {
                                struct BoneLink
                                {
                                    const char* From;
                                    const char* To;
                                };

                                static BoneLink boneLines[] = {
                                    {"Head", "neck_01"},
                                    {"neck_01", "spine_03"},
                                    {"spine_03", "spine_02"},
                                    {"spine_02", "spine_01"},
                                    {"spine_01", "pelvis"},

                                    {"neck_01", "upperarm_r"},
                                    {"upperarm_r", "lowerarm_r"},
                                    {"lowerarm_r", "hand_r"},

                                    {"neck_01", "upperarm_l"},
                                    {"upperarm_l", "lowerarm_l"},
                                    {"lowerarm_l", "hand_l"},

                                    {"pelvis", "thigh_r"},
                                    {"thigh_r", "calf_r"},
                                    {"calf_r", "foot_r"},

                                    {"pelvis", "thigh_l"},
                                    {"thigh_l", "calf_l"},
                                    {"calf_l", "foot_l"}
                                };

                                FVector2D from2D, to2D;

                                for (auto& bone : boneLines)
                                {
                                    FVector from = Player->GetBonePos(bone.From, {});
                                    FVector to = Player->GetBonePos(bone.To, {});

                                    if (W2S(from, &from2D) && W2S(to, &to2D))
                                    {
                                        K2_DrawLineUI(Canvas,from2D, to2D, 1.5f, Color);
                                    }
                                }
                            }
                            if (false && Cheat::Esp::Health) // health via SHM now
                            {
                                float CurHP = std::max(0.f, std::min(Player->Health, Player->HealthMax));
                                float MaxHP = Player->HealthMax;
                                float KnockHealth = Player->NearDeathBreath;

                                FLinearColor HPColor;
                                float HealthPercentage;
                                bool isKnocked = (CurHP <= 0.f);
                                if (isKnocked) {
                                    HPColor = FLinearColor(1.0f, 0.0f, 0.0f, 1.0f); // Red = knocked
                                    HealthPercentage = (MaxHP > 0.f) ? (KnockHealth / MaxHP) : 0.f;
                                } else {
                                    HPColor = FLinearColor(0.0f, 1.0f, 0.0f, 1.0f); // Green = alive
                                    HealthPercentage = (MaxHP > 0.f) ? (CurHP / MaxHP) : 0.f;
                                }

                                FVector HeadLocation = Player->GetHeadLocation(true);
                                HeadLocation.Z += 25.f;

                                FVector2D HeadScreenPos;
                                if (W2S(HeadLocation, &HeadScreenPos))
                                {
                                    float BarWidth = 38.0f, BarHeight = 4.0f;
                                    HeadScreenPos.X -= BarWidth / 2;
                                    HeadScreenPos.Y -= BarHeight * 2.0f;
                                    DrawFilledRect(Canvas, FVector2D(HeadScreenPos.X, HeadScreenPos.Y), BarWidth, BarHeight, FLinearColor(0.1f, 0.1f, 0.1f, 0.8f));
                                    DrawFilledRect(Canvas, HeadScreenPos, BarWidth * HealthPercentage, BarHeight, HPColor);
                                }
                            }
                        }
                    }
                }
                


                if (Cheat::Esp::Vehicle::Name) {
                    if (Actor->IsA(ASTExtraVehicleBase::StaticClass())) {
                        auto Vehicle = (ASTExtraVehicleBase*)Actor;

                        if (!Vehicle->Mesh)
                            continue;

                        float Distance = Vehicle->GetDistanceTo(Cheat::localPlayer) / 100.f;

                        if (Distance < 10.0f)
                            continue;

                        FVector2D Screen;

                        if (W2S(Vehicle->K2_GetActorLocation(), &Screen)) {
                            // ── SHM: vehicle data — no internal drawing ──────────
                            if (shmFrame.vehicleCount < ARK_MAX_VEHICLES) {
                                ArkEspVehicle& ev = shmFrame.vehicles[shmFrame.vehicleCount++];
                                ev.screenX   = Screen.X / (float)screenWidth;
                                ev.screenY   = Screen.Y / (float)screenHeight;
                                ev.distance  = Distance;
                                ev.hasEnemy  = 0;
                                std::string vn = GetVehicleName(Vehicle);
                                strncpy(ev.name, vn.c_str(), 31); ev.name[31] = 0;
                                if (Vehicle->VehicleSeats) {
                                    auto& Occ = Vehicle->VehicleSeats->SeatOccupiers;
                                    for (int s = 0; s < Occ.Num(); s++) {
                                        if (Occ[s] && Occ[s] != Cheat::localPlayer)
                                            ev.hasEnemy = 1;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (Cheat::Esp::Count && (totalEnemies > 0 || totalBots > 0)) {
                g_CntEnemies = (int)totalEnemies;
                g_CntBots    = (int)totalBots;
                g_CntShow    = false; // DISABLED: EspOverlay draws counter via SHM
                // Internal Canvas counter drawing DISABLED:
                // EspOverlay.java draws the counter from SHM (enemyCount/botCount).
                // Drawing it here too would cause double counter on screen.
                // if (!g_EspUseImGui) { ... Canvas DrawFilledRect ... }
            } else {
                g_CntShow = false;
            }

            // ── HUD data for overlay ──────────────────────────────────────────
            shmFrame.fps = g_ShmFpsDisplay;
            {
                char tmpExp[48] = {0};
                KC_BuildExpiryStr(tmpExp, sizeof(tmpExp));
                memcpy(shmFrame.expiryText, tmpExp, 47);
                shmFrame.expiryText[47] = 0;
            }

            // Publish ESP texts for ImGui pass
            if (g_EspUseImGui) g_EspTextsRender = g_EspTexts;

            // ── SHM: finalize and write frame ─────────────────────────────
            shmFrame.enemyCount = totalEnemies;
            shmFrame.botCount   = totalBots;
            ShmESP_Write(shmFrame);
        }
    }
  }

void DrawMemory()
{
    if (Cheat::localPlayer && Cheat::localController)
    {
       

      static ULocalPlayer* UlocalPlayer = UObject::FindObject<ULocalPlayer>("LocalPlayer Transient.UAEGameEngine_1.LocalPlayer_1");
    if (!UlocalPlayer) {return;}
    static const auto OrigView = UlocalPlayer->AspectRatioAxisConstraint;
    if (Cheat::Memory::WideView) {
        UlocalPlayer->AspectRatioAxisConstraint = EAspectRatioAxisConstraint::AspectRatio_MaintainYFOV;
    } else if (UlocalPlayer->AspectRatioAxisConstraint != OrigView) {
        UlocalPlayer->AspectRatioAxisConstraint = OrigView;
    }

   
    }
}

FRotator operator-(const FRotator& A, const FRotator& B)
{
    FRotator Result;
    Result.Pitch = A.Pitch - B.Pitch;
    Result.Yaw = A.Yaw - B.Yaw;
    Result.Roll = A.Roll - B.Roll;
    return Result;
}

FRotator NormalizeRotator(const FRotator& Rotator)
{
    FRotator NormalizedRotator = Rotator;

    while (NormalizedRotator.Pitch > 180.0f)
        NormalizedRotator.Pitch -= 360.0f;
    while (NormalizedRotator.Pitch < -180.0f)
        NormalizedRotator.Pitch += 360.0f;

    while (NormalizedRotator.Yaw > 180.0f)
        NormalizedRotator.Yaw -= 360.0f;
    while (NormalizedRotator.Yaw < -180.0f)
        NormalizedRotator.Yaw += 360.0f;

    while (NormalizedRotator.Roll > 180.0f)
        NormalizedRotator.Roll -= 360.0f;
    while (NormalizedRotator.Roll < -180.0f)
        NormalizedRotator.Roll += 360.0f;

    return NormalizedRotator;
}

template <typename T>
T Clamp(const T& Value, const T& Min, const T& Max)
{
    if (Value < Min)
        return Min;
    else if (Value > Max)
        return Max;
    else
        return Value;
}

void AimBotThread()
{
  if (Cheat::localPlayer && Cheat::localController){

    if(Cheat::Aimbot::Enable){
    auto STExtraBaseCharacter = Cheat::localController->STExtraBaseCharacter;
    if (STExtraBaseCharacter && !isObjectInvalid(STExtraBaseCharacter))
    {
        if (STExtraBaseCharacter->CurrentVehicle == nullptr)
        {
            if (Cheat::localPlayer->bIsWeaponFiring)
            {
                auto WeaponManagerComponent = Cheat::localPlayer->WeaponManagerComponent;
                if (WeaponManagerComponent)
                {
                    auto CurrentWeaponReplicated =
                        (ASTExtraShootWeapon*)WeaponManagerComponent->CurrentWeaponReplicated;

                    if (CurrentWeaponReplicated)
                    {
                        auto WeaponSlot = CurrentWeaponReplicated->GetWeaponSlot();
                        if (WeaponSlot == ESurviveWeaponPropSlot::SWPS_MainShootWeapon1 ||
                            WeaponSlot == ESurviveWeaponPropSlot::SWPS_MainShootWeapon2 ||
                            WeaponSlot == ESurviveWeaponPropSlot::SWPS_SubShootWeapon)
                        {
                            auto PlayerCameraManager = Cheat::localController->PlayerCameraManager;
                            if (PlayerCameraManager)
                            {
                                auto ShootWeapon = CurrentWeaponReplicated->ShootWeaponComponent;
                                if (ShootWeapon)
                                {
                                    auto Target = GetTargetByCross();
                                    if (Target)
                                    {
                                        FVector targetAimPos = Target->GetBonePos("spine_03", {});
                                        bool IsVisible = false;

                                        if (Target->CurrentVehicle)
                                        {
                                            IsVisible = Cheat::localController->LineOfSightTo(
                                                Target->CurrentVehicle, {}, true
                                            );
                                        }
                                        else
                                        {
                                            IsVisible = Cheat::localController->LineOfSightTo(
                                                PlayerCameraManager, targetAimPos, true
                                            );
                                        }

                                        if (IsVisible)
                                        {
                                            float BulletFireSpeed = CurrentWeaponReplicated->GetBulletFireSpeedFromEntity();
                                            float Distance = Cheat::localPlayer->GetDistanceTo(Target);
                                            float TimeToTravel = Distance / BulletFireSpeed;

                                            FVector Velocity = Target->GetVelocity();
                                            if (Velocity.X != 0 || Velocity.Y != 0 || Velocity.Z != 0)
                                            {
                                                targetAimPos = targetAimPos + Velocity * TimeToTravel;
                                            }

                                            if (Cheat::localPlayer->bIsGunADS)
                                            {
                                                float dist = Distance / 100.0f;
                                                targetAimPos.Z -= dist * 1.3f;
                                            }

                                            FVector StartLoc = PlayerCameraManager->CameraCache.POV.Location;
                                            FRotator AimRotation = ToRotator(StartLoc, targetAimPos);
                                            FRotator CurrentRotation = PlayerCameraManager->CameraCache.POV.Rotation;

                                            FRotator DeltaRotation = NormalizeRotator(AimRotation - CurrentRotation);

                                            float smooth = std::max(1.0f, Cheat::Aimbot::Smoothness);
                                            float clampR = 250.0f / smooth;
                                            float speedD = std::max(1.0f, smooth * 0.4f);
                                            DeltaRotation.Yaw   = Clamp(DeltaRotation.Yaw,   -clampR, clampR);
                                            DeltaRotation.Pitch = Clamp(DeltaRotation.Pitch, -clampR, clampR);

                                            float Yaw   = DeltaRotation.Yaw   / speedD;
                                            float Pitch = DeltaRotation.Pitch / speedD;

                                            Cheat::localPlayer->AddControllerYawInput(Yaw);
                                            Cheat::localPlayer->AddControllerPitchInput(Pitch);
                                           }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
     }
  }
}


int Hook_DataPointer(uintptr_t ptr_addr, void* hook_func, void** original_func)
{
    if (!ptr_addr || !hook_func) return 1;

    void** target_ptr = (void**)ptr_addr;
    void* orig = *target_ptr;

    if (original_func) *original_func = orig;

    g_mprotect(ptr_addr, sizeof(void*), PROT_READ | PROT_WRITE);
    *target_ptr = hook_func;
    g_mprotect(ptr_addr, sizeof(void*), PROT_READ);

    return 0;
}

static int bulletCounter = 0;

void AutoEspOn() {

    Cheat::Esp::Line = true;
    Cheat::Esp::Skeleton = true;
    Cheat::BulletTrack::IgnoreBot = false;   
    Cheat::BulletTrack::IgnoreKnock  = false;
    Cheat::BulletTrack::VisCheck = true;
    Cheat::Esp::Name = true;
    Cheat::Esp::Health = true;
    Cheat::Esp::Count = true;
    
}


using MessageBoxExtType = int (*)(unsigned int, const char16_t *, const char16_t *, unsigned int);
MessageBoxExtType MessageBoxExt = nullptr;

std::u16string StringToU16(const std::string &str)
{
    std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> convert;
    return convert.from_bytes(str);
}

int ShowMessage(const std::string &Message, const std::string &Caption)
{
    if (!MessageBoxExt)
        return -1;

    std::u16string v1 = StringToU16(Message);
    std::u16string v2 = StringToU16(Caption);

    // 0x00000003 = MB_YESNOCANCEL, 0x00000040 = MB_ICON_INFORMATION
    return MessageBoxExt(1, v1.c_str(), v2.c_str(), 0x00000003 | 0x00000040);
}

class FMessageBox
{
public:
    static FMessageBox StaticClass()
    {
        return FMessageBox();
    }

    FMessageBox &SetCaption(const std::string &caption)
    {
        caption_ = caption;
        return *this;
    }

    FMessageBox &SetText(const std::string &text)
    {
        text_ = text;
        return *this;
    }

    FMessageBox &AddButton(const std::string &buttonText)
    {
        buttons_.push_back(buttonText);
        return *this;
    }

    int Show();

private:
    FMessageBox() = default;

    std::string caption_;
    std::string text_;
    std::vector<std::string> buttons_;

    void logJavaException(JNIEnv *env)
    {
        if (!env->ExceptionCheck())
            return;

        jthrowable exception = env->ExceptionOccurred();
        env->ExceptionClear();

        jclass throwableClass = env->FindClass("java/lang/Throwable");
        jmethodID toStringMethod = env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;");
        jstring exceptionStr = (jstring)env->CallObjectMethod(exception, toStringMethod);

        const char *exceptionCStr = env->GetStringUTFChars(exceptionStr, nullptr);
        //LOGI("Java Exception: %s", exceptionCStr);

        env->ReleaseStringUTFChars(exceptionStr, exceptionCStr);
        env->DeleteLocalRef(exceptionStr);
        env->DeleteLocalRef(throwableClass);
        env->DeleteLocalRef(exception);
    }
};

int FMessageBox::Show()
{
    if (!g_App)
    {
        return -1;
    }

    auto activity = g_App->activity;
    if (!activity)
    {
        return -1;
    }

    auto vm = activity->vm;
    if (!vm)
    {
        return -1;
    }

    auto object = activity->clazz;
    if (!object)
    {
        return -1;
    }

    JNIEnv *env = nullptr;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env)
    {
        return -1;
    }

    jclass contextClass = env->GetObjectClass(object);
    jmethodID getClassLoader = env->GetMethodID(contextClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject classLoader = env->CallObjectMethod(object, getClassLoader);

    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring strClassName = env->NewStringUTF("com.epicgames.ue4.MessageBox01");
    jclass messageBoxClass = (jclass)env->CallObjectMethod(classLoader, loadClass, strClassName);
    if (!messageBoxClass)
    {
        env->ExceptionClear();
        vm->DetachCurrentThread();
        return -1;
    }
    jmethodID constructor = env->GetMethodID(messageBoxClass, "<init>", "()V");
    if (!constructor)
    {
        env->ExceptionClear();
        env->DeleteLocalRef(messageBoxClass);
        vm->DetachCurrentThread();
        return -1;
    }
    jobject messageBoxObj = env->NewObject(messageBoxClass, constructor);
    if (!messageBoxObj)
    {
        env->ExceptionClear();
        env->DeleteLocalRef(messageBoxClass);
        vm->DetachCurrentThread();
        return -1;
    }
    jmethodID setCaptionMethod = env->GetMethodID(messageBoxClass, "setCaption", "(Ljava/lang/String;)V");
    jmethodID setTextMethod = env->GetMethodID(messageBoxClass, "setText", "(Ljava/lang/String;)V");
    jmethodID addButtonMethod = env->GetMethodID(messageBoxClass, "addButton", "(Ljava/lang/String;)V");
    jmethodID showMethod = env->GetMethodID(messageBoxClass, "show", "()I");
    if (!setCaptionMethod || !setTextMethod || !addButtonMethod || !showMethod)
    {
        env->ExceptionClear();
        env->DeleteLocalRef(messageBoxObj);
        env->DeleteLocalRef(messageBoxClass);
        vm->DetachCurrentThread();
        return -1;
    }
    jstring caption = env->NewStringUTF(caption_.c_str());
    if (caption)
    {
        env->CallVoidMethod(messageBoxObj, setCaptionMethod, caption);
        env->DeleteLocalRef(caption);
        if (env->ExceptionCheck())
        {
            //LOGI("Exception in setCaption");
            logJavaException(env);
        }
    }
    jstring text = env->NewStringUTF(text_.c_str());
    if (text)
    {
        env->CallVoidMethod(messageBoxObj, setTextMethod, text);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck())
        {
            logJavaException(env);
        }
    }
    for (const auto &buttonText : buttons_)
    {
        jstring jButtonText = env->NewStringUTF(buttonText.c_str());
        if (jButtonText)
        {
            env->CallVoidMethod(messageBoxObj, addButtonMethod, jButtonText);
            env->DeleteLocalRef(jButtonText);
            if (env->ExceptionCheck())
            {
                logJavaException(env);
            }
        }
    }
    //LOGI("Calling show method...");
    jint result = env->CallIntMethod(messageBoxObj, showMethod);
    if (env->ExceptionCheck())
    {
        logJavaException(env);
        result = -1;
    }
    else
    {
       
    }
    env->DeleteLocalRef(messageBoxObj);
    env->DeleteLocalRef(messageBoxClass);
    vm->DetachCurrentThread();
    return result;
}

int MsgBoxJava(const std::string &text,
               const std::string &caption = "Notice",
               const std::vector<std::string> &buttons = {"OK"})
{
    FMessageBox box = FMessageBox::StaticClass()
        .SetCaption(caption)
        .SetText(text);

    for (const auto &btn : buttons)
        box.AddButton(btn);

    int result = box.Show();
    //LOGI("showMessage button index: %d", result);

    if (result >= 0 && result < buttons.size())
        //LOGI("User pressed: %s", buttons[result].c_str());

    return result;
}

#define MassageBox(msg, caption, ...) MsgBoxJava(msg, caption, std::vector<std::string>{__VA_ARGS__})

void ResolveMessageBox()
{
    *(uintptr_t *)&MessageBoxExt = Cheat::libUE4Base + Cheat::msgboxext;
}


void MessageMain()
{
    std::string title = OBFUSCATE("");
    int msgboxResult = MassageBox(OBFUSCATE("Select the feature you would like to activate from the list below."),title,OBFUSCATE("SAFE BT"),OBFUSCATE("BRUTAL BT"), OBFUSCATE("AIMBOT"));

    if (msgboxResult == 0)
    {
        Cheat::BulletTrack::Safe = true;
        Cheat::BulletTrack::BulletRange = 150.0f;
        Cheat::Mode = "Safe BT";
    }
    else if (msgboxResult == 1)
    {
        Cheat::BulletTrack::Enable = true;
        Cheat::BulletTrack::BulletRange = 500.0f;
        Cheat::Mode = "Brutal BT";
    }
    else
    {
        Cheat::Aimbot::Enable = true;
        Cheat::BulletTrack::BulletRange = 150.0f;
        Cheat::Mode = "Aim";
        FOVSizea = 140.f;
    }


    int ipad = ShowMessage("[YES] : IPAD VIEW ON \n[NO] : IPAD VIEW OFF", "FEATURES");

    if (ipad == 1) 
    {
        Cheat::Memory::WideView = true;  
    }
    else
    {
        Cheat::Memory::WideView = false;
    }
}


void InitializeCheat()
{
    ResolveMessageBox();
    MessageMain();
}



void* (*orig_PostRenderOriginal)(UGameViewportClient*, UCanvas*);
void* _PostRenderHook(UGameViewportClient* Viewport, UCanvas* Canvas) {
    GetLocalPlayer();
    RenderESP(Canvas);
    DrawMemory();
    AimBotThread();

    // ── GUN SKIN — per-frame synData remap (same as ArkJavaTcp2DistNameIssue) ──
    if (ENABLE_GUN_SKIN && g_SkinsEnabled && GunSkin && Cheat::localPlayer) {
        try {
        auto* WeaponManager = Cheat::localPlayer->WeaponManagerComponent;
        if (WeaponManager && (uintptr_t)WeaponManager > 0x100000) {
            auto propSlot = WeaponManager->GetCurrentUsingPropSlot();
            if ((int)propSlot.GetValue() >= 1 && (int)propSlot.GetValue() <= 4) {
                auto* Weapon = (ASTExtraShootWeapon*)WeaponManager->CurrentWeaponReplicated;
                if (Weapon && (uintptr_t)Weapon > 0x100000) {
                    auto& UnrealHacks = Weapon->synData;
                    if (UnrealHacks.Num() > 0 && UnrealHacks.Num() < 50) {
                        int weaponID = Weapon->GetWeaponID();
                        if (weaponID > 0 && weaponID < 200000) {
                        int gi = -1;
                        for (int k = 0; k < (int)g_KCGunDB.size() && k < 60; k++) {
                            if (g_KCGunDB[k].weaponID == weaponID) { gi = k; break; }
                        }
                        int bodySkn = 0;
                        std::unordered_map<int,int>* skinMap = nullptr;
                        if (gi >= 0 && g_UserGunBodySkin[gi] > 0) {
                            bodySkn = g_UserGunBodySkin[gi];
                            ModSkinsID[weaponID] = bodySkn;
                            for (int si = 1; si < (int)g_KCGunDB[gi].skins.size(); si++) {
                                auto it = g_KCGunDB[gi].skins[si].map.find(weaponID);
                                if (it != g_KCGunDB[gi].skins[si].map.end() && it->second == bodySkn) {
                                    skinMap = &g_KCGunDB[gi].skins[si].map; break;
                                }
                            }
                        } else if (gi >= 0 && g_KCGunSelIdx[gi] > 0) {
                            int sel = g_KCGunSelIdx[gi];
                            if (sel < (int)g_KCGunDB[gi].skins.size()) {
                                skinMap = &g_KCGunDB[gi].skins[sel].map;
                                auto it = skinMap->find(weaponID);
                                if (it != skinMap->end()) { bodySkn = it->second; ModSkinsID[weaponID] = bodySkn; }
                            }
                        } else {
                            std::unordered_map<int,int>* m = nullptr;
                            switch (weaponID) {
                            case 101004: m = &M416SkinsID;        break;
                            case 101001: m = &AKMSkinsID;         break;
                            case 101002: m = &M16A4SkinsID;       break;
                            case 101003: m = &SCARLSkinsID;       break;
                            case 101005: m = &GROZASkinsID;       break;
                            case 101006: m = &AUGSkinsID;         break;
                            case 101007: m = &QBZSkinsID;         break;
                            case 101008: m = &M762SkinsID;        break;
                            case 101102: m = &ACE32SkinsID;       break;
                            case 101101: m = &ASMAbakanSkinsID;   break;
                            case 101100: m = &FAMASSkinsID;       break;
                            case 103003: m = &AWMSkinsID;         break;
                            case 103002: m = &M24SkinsID;         break;
                            case 103001: m = &KAR98KSkinsID;      break;
                            case 103012: m = &AMRSkinsID;         break;
                            case 103102: m = &DSRSkinsID;         break;
                            case 103007: m = &MK14SkinsID;        break;
                            case 103004: m = &SKSSkinsID;         break;
                            case 103006: m = &MINI14SkinsID;      break;
                            case 103009: m = &SLRSkinsID;         break;
                            case 103100: m = &MK12SkinsID;        break;
                            case 105002: m = &DP28SkinsID;        break;
                            case 105010: m = &MG3SkinsID;         break;
                            case 105001: m = &M249SkinsID;        break;
                            case 102001: m = &UZISkinsID;         break;
                            case 102002: m = &UMP45SkinsID;       break;
                            case 102003: m = &VECTORSKinsID;      break;
                            case 102004: m = &THOMPSONSkinsID;    break;
                            case 102005: m = &BIZONSkinsID;       break;
                            case 103005: m = &VSSSkinsID;         break;
                            case 102105: m = &P90SkinsID;         break;
                            case 104003: m = &S12KSkinsID;        break;
                            case 104002: m = &S1897SkinsID;       break;
                            case 104004: m = &DBSSkinsID;         break;
                            case 101012: m = &HoneyBadgerSkinsID; break;
                            case 108004: m = &PANSkinsID;         break;
                            case 108001: m = &MacheteSkinsID;     break;
                            case 108005: m = &DaggerSkinsID;      break;
                            default: break;
                            }
                            skinMap = m;
                        }
                        if (skinMap && !skinMap->empty()) {
                            bool skinChanged = false;
                            for (int i = 0; i < UnrealHacks.Num(); i++) {
                                auto& entry = UnrealHacks[i];
                                auto it = skinMap->find(entry.DefineID.TypeSpecificID);
                                if (it != skinMap->end() && entry.DefineID.TypeSpecificID != it->second) {
                                    entry.DefineID.TypeSpecificID = it->second;
                                    skinChanged = true;
                                }
                            }
                            if (skinChanged) Weapon->OnRep_AvatarMeshChanged();
                        } else if (bodySkn > 0) {
                            bool skinChanged = false;
                            for (int i = 0; i < UnrealHacks.Num(); i++) {
                                auto& entry = UnrealHacks[i];
                                if (entry.DefineID.TypeSpecificID == weaponID) {
                                    entry.DefineID.TypeSpecificID = bodySkn;
                                    skinChanged = true; break;
                                }
                            }
                            if (skinChanged) Weapon->OnRep_AvatarMeshChanged();
                        }
                        }
                    }
                }
            }
        }
        } catch (...) {}
    }

    // ── Backpack / Helmet / Parachute / Glider skin remap (per-frame C++) ───────
    if (Cheat::localPlayer && Cheat::localPlayer->AvatarComponent2) {
        auto* NetAvatarComp = (FNetAvatarSyncData*)((uintptr_t)Cheat::localPlayer->AvatarComponent2 + 0x0440);
        if (NetAvatarComp) {
            auto& Slots = NetAvatarComp->SlotSyncData;
            bool updated = false;
            for (int i = 0; i < Slots.Num(); i++) {
                int& id = Slots[i].ItemId;
                auto findNewId = [&](const std::unordered_map<int,int>& map) -> bool {
                    auto it = map.find(id);
                    if (it != map.end() && id != it->second) {
                        id = it->second;
                        updated = true;
                        return true;
                    }
                    return false;
                };
                if (findNewId(Bagpack) || findNewId(Helmet) || findNewId(Parachute) || findNewId(GliderSkin) || findNewId(ThrowableSkin)) continue;
            }
            if (updated)
                Cheat::localPlayer->AvatarComponent2->OnRep_BodySlotStateChanged();
        }
    }

    if (ENABLE_GUN_SKIN && g_SkinsEnabled && GunSkin) {
        try { KC_DrawDeadBoxSkins(); } catch (...) {}
    }

    return orig_PostRenderOriginal(Viewport, Canvas);
}








__int64_t (*ozero_return)();
__int64_t zero_return()
{
	return 0;
}





void *(*orig_LaunchBP)(ASTExtraShootWeaponBulletBase* pObj, float Speed, AActor* InOwningWeapon, AActor* InInstigator, FTransform* InTrans, FTransform* OutTrans);
void *hook_LaunchBP(ASTExtraShootWeaponBulletBase* pObj, float Speed, AActor* InOwningWeapon, AActor* InInstigator, FTransform* InTrans, FTransform* OutTrans) {
  
 if (Cheat::BulletTrack::Safe) {
        ASTExtraPlayerCharacter* Target = GetTargetByCross();
        if (Target) {
            bool triggerOk = Cheat::localPlayer->bIsWeaponFiring || Cheat::localPlayer->bIsGunADS;
            if (triggerOk) {
                FVector targetAimPos = Target->GetBonePos("spine_02", {});


                auto CurrentWeaponReplicated = pObj->OwningShootWeapon;
                if (CurrentWeaponReplicated) {
                    CurrentWeaponReplicated->ShootMode = EShootWeaponShootMode::SWST_TraceTarget;
                    auto ShootWeaponComponent = CurrentWeaponReplicated->ShootWeaponComponent;
                    if (ShootWeaponComponent) {
                                bulletCounter++;
                                if (bulletCounter == 2) {
                                      bulletCounter = 0;
          return orig_LaunchBP(pObj, Speed, InOwningWeapon, InInstigator, InTrans, OutTrans);
            } else {
          FVector gunlocation = UKismetMathLibrary::Subtract_VectorVector(targetAimPos, Cheat::localController->PlayerCameraManager->CameraCache.POV.Location);
          FRotator aimrotation = UKismetMathLibrary::Conv_VectorToRotator(gunlocation);
         OutTrans->Rotation = SDK::UGameLuaAPI::RotatorToQuat(aimrotation);
                     }
                    }
                }
            }
        }
      }
  
  return orig_LaunchBP(pObj, Speed, InOwningWeapon, InInstigator, InTrans, OutTrans);
}





size_t hstrlen(const char *s)
{
	
	print_backtrace_logs(s);
	return strlen(s);
}


#include <vector>
#include <random>

std::vector<float> zOffsets = {-15.0f/*, 11.0f*/, -10.0f, -22.0f, -11.0f, /*15.0f*/};
std::vector<float> xOffsets = {1.0f, 2.0f};
int zOffsetIndex = 0;
int xOffsetIndex = 0;

std::random_device rd;
std::mt19937 gen(rd());
std::uniform_real_distribution<float> randomOffset(-2.5f, 2.5f);  // Small random variation

void (*orig_shoot_event)(USTExtraShootWeaponComponent *thiz, FVector start, FRotator rot, ASTExtraShootWeapon *weapon, int unk1, float a6, float a7, float a8) = 0;
void shoot_event(USTExtraShootWeaponComponent *thiz, FVector start, FRotator rot, ASTExtraShootWeapon *weapon, int unk1, float a6, float a7, float a8) {
    if (Cheat::BulletTrack::Enable) {
        if (Cheat::BulletTrack::Safe) {
            return orig_shoot_event(thiz, start, rot, weapon, unk1, a6, a7, a8);
        }
        auto Target = GetTargetByCross();
        if (Target) {
            FVector targetAimPos = Target->GetBonePos("spine_03", {});


            targetAimPos.Z -= (zOffsets[zOffsetIndex] + randomOffset(gen));
            targetAimPos.X -= (xOffsets[xOffsetIndex] + randomOffset(gen));
            targetAimPos.Y -= randomOffset(gen);


            zOffsetIndex = (zOffsetIndex + 1) % zOffsets.size();
            xOffsetIndex = (xOffsetIndex + 1) % xOffsets.size();


            FRotator newAimRotation = ToRotator(start, targetAimPos);

            return orig_shoot_event(thiz, targetAimPos, newAimRotation, weapon, unk1, a6, a7, a8);
        }
    }

    return orig_shoot_event(thiz, start, rot, weapon, unk1, a6, a7, a8);
}

#include <sys/stat.h>
#include <unistd.h>
#include <string>
#include <android/log.h>
//https://t.me/MILANWG
uintptr_t libanogsBase = 0;
//Telegram Link - https://t.me/MILANWG
uintptr_t get_module_base(pid_t pid, const char *module_name) {
    char filename[32], line[512];
    uintptr_t addr = 0;
    snprintf(filename, sizeof(filename), "/proc/%d/maps", pid);
    FILE *fp = fopen(filename, "r");

    if (fp != NULL) {
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, module_name)) {
                sscanf(line, "%lx-%*lx", &addr);
                break;
            }
        }
        fclose(fp);
    }

    return addr;
}



extern "C" void InitHooks();






__attribute__((__annotate__(("fla bcf sub sobf split"))))
void *main_thread()
{ 
    
    detect();
    pid_t Pid = getpid();
    Cheat::libUE4Base = Tools::GetBaseAddress("libUE4.so");

     while (!Cheat::libUE4Base)
    {
        Cheat::libUE4Base = Tools::GetBaseAddress("libUE4.so");
        sleep(1);
    }

    Cheat::libanogsBase = Tools::GetBaseAddress("libanogs.so");

     while (!Cheat::libanogsBase)
    {
       Cheat::libanogsBase = Tools::GetBaseAddress("libanogs.so");
        sleep(1);
    }
    

     while (!g_App)
    {
        g_App = *(android_app **)(Cheat::libUE4Base + Cheat::GNativeAndroidApp_Offset);
        sleep(1);
    }

    FName::GNames = GetGNames();
    while (!FName::GNames)
    {
        FName::GNames = GetGNames();
        sleep(1);
    }

    static bool loadFont = false;
    if (!loadFont) {
        pthread_t t;
        pthread_create(&t, 0, LoadFont, 0);
        loadFont = true;
    }

    UObject::GUObjectArray = (FUObjectArray *)(Cheat::libUE4Base + Cheat::GUObject_Offset);
    items_data = json::parse(JSON_ITEMS);
    ResolveMessageBox();
pthread_t loginTid;
pthread_create(&loginTid, nullptr, loda_thread, nullptr);
pthread_join(loginTid, nullptr);
if(bValid)
{
	std::thread(InitHooks).detach();
*(uintptr_t *) &K2_DrawLineUI = Cheat::libUE4Base + Cheat::line;
*(uintptr_t *) &K2_DrawTextUI = Cheat::libUE4Base + Cheat::text;
StartTcpFeatureServer();
if (g_App && g_App->activity) StreamESP_Init(g_App->activity);
A64HookFunction((void *)(Cheat::libUE4Base + Cheat::fakedamage1), (void *)zero_return, (void **)&ozero_return);
A64HookFunction((void *)(Cheat::libUE4Base + Cheat::launchbp), (void *)hook_LaunchBP, (void **)&orig_LaunchBP);
A64HookFunction((void *)(Cheat::libUE4Base + Cheat::shootevent), (void *)shoot_event, (void **)&orig_shoot_event);
A64HookFunction((void *)(Cheat::libUE4Base + Cheat::renderoffset), (void *)_PostRenderHook , (void **) &orig_PostRenderOriginal);
#if (KC_RENDERER_OFFSET != 0)
A64HookFunction((void *)(Cheat::libUE4Base + KC_RENDERER_OFFSET), (void *)hook_renderer, (void **)&orig_renderer);
#endif

// ── Lua Twitter login fix ──────────────────────────────────────────────────
// 1. Resolve pcall (direct pointer — no hook needed)
kc_lua.pcall = (decltype(kc_lua.pcall))(Cheat::libUE4Base + Cheat::lua_pcall);
// 2. Hook luaL_loadbufferx to capture the game's Lua state
A64HookFunction((void *)(Cheat::libUE4Base + Cheat::lua_loadbufferx),
                (void *)KC_hooked_loadbufferx,
                (void **)&KC_orig_loadbufferx);
// 3. loadBuffer for our injection = ORIGINAL trampoline (avoids re-hooking)
kc_lua.loadBuffer = (decltype(kc_lua.loadBuffer))KC_orig_loadbufferx;
// 4. Hook lua_sub (ProcessEvent-equivalent) — injection fires on game thread
A64HookFunction((void *)(Cheat::libUE4Base + Cheat::lua_sub),
                (void *)KC_hooked_ProcessEvent,
                (void **)&KC_orig_ProcessEvent);

// ── Skin system init ───────────────────────────────────────────────────────
KC_InitGunDB();
SkinCfg_Load();
KC_LoadSavedSkins();
KC_LoadUserPrefs();
KC_SyncModSkinsIDFromPrefs();
KC_InitUserPrefs();

// ── Kill message hook (only when skins enabled) ────────────────────────────
if (g_SkinsEnabled && Cheat::KillMSG) {
    A64HookFunction(
        (void *)(Cheat::libUE4Base + Cheat::KillMSG),
        (void *)kill_message_event,
        (void **)&orig_kill_message_event);
}

}


    return nullptr;
}

__attribute__ ((constructor)) 
void _init()
{
  std::thread(main_thread).detach();

  
  
}






