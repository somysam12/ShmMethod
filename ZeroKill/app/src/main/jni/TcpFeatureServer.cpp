// ═══════════════════════════════════════════════════════════════════════
//  TcpFeatureServer.cpp  —  Local TCP bridge (Aurix-style)
//
//  Overlay APK (Floating.java) sends feature toggles.
//  Injected lib (game process) runs this server and flips Cheat::* bools.
//  Loopback 127.0.0.1 → cross-process same-device.
//
//  Protocol (text, newline-terminated):
//      "aimbot=1\n"       → Aimbot ON  (BulletTrack auto-OFF — exclusive)
//      "aimbot=0\n"       → Aimbot OFF
//      "btrack=1\n"       → BulletTrack ON + Safe BT ON (Aimbot auto-OFF)
//      "btrack=0\n"       → BulletTrack OFF + Safe BT OFF
//      "esp=1/0\n"        → ESP all on/off
//      "hideesp=1/0\n"    → Stream Mode (HideESP)
//      "wideview=1/0\n"   → iPad / Wide View
//      "btrange=XX\n"     → Set BT bullet range (1-150)
//      "smooth=XX\n"      → Set Aimbot smoothness (1-50)
//      "grenadewarn=1/0\n"→ Grenade Warning ESP
//      "ping\n"           → server replies "pong\n"
// ═══════════════════════════════════════════════════════════════════════

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <string>
#include <thread>
#include <chrono>
#include <android/log.h>

#define TCP_LOG_TAG "ArkTCP"
#define TCPLOGI(...) __android_log_print(ANDROID_LOG_INFO,  TCP_LOG_TAG, __VA_ARGS__)
#define TCPLOGE(...) __android_log_print(ANDROID_LOG_ERROR, TCP_LOG_TAG, __VA_ARGS__)

#ifndef ARK_TCP_PORT
#define ARK_TCP_PORT 28900
#endif

// ── Feature vars live in definition.h / StreamESP.h ─────────────────────────
namespace Cheat {
    namespace Aimbot {
        extern bool  Enable;
        extern bool  VisCheck;
        extern bool  IgnoreKnock;
        extern bool  IgnoreBot;
        extern float Smoothness;
        extern float Radius;
        extern float RecoilCompression;
    }
    namespace BulletTrack {
        extern bool  Enable;
        extern bool  Safe;
        extern bool  VisCheck;
        extern bool  IgnoreKnock;
        extern bool  IgnoreBot;
        extern float BulletRange;
    }
    namespace Esp {
        extern bool Line, Skeleton, Health, Distance, Name, Count, Alert;
        namespace Vehicle { extern bool Name, Health, Fuel; }
    }
    namespace Memory {
        extern bool WideView;
        extern bool MagicBullet;
    }
    extern std::string Mode;
}
// g_StreamMode is static in StreamESP.h — exposed via this setter in main.cpp
extern void TCP_SetStreamMode(bool on);
extern void TCP_SetGrenadeWarning(bool on);


// ── Feature appliers ─────────────────────────────────────────────────────────
static void ApplyAimbot(bool on) {
    if (on) {
        Cheat::BulletTrack::Enable       = false;
        Cheat::BulletTrack::Safe         = false;
        Cheat::Memory::MagicBullet       = false;
        Cheat::Aimbot::Enable            = true;
        Cheat::Aimbot::VisCheck          = true;
        Cheat::Aimbot::Smoothness        = 6.0f;  // default
        Cheat::Aimbot::Radius            = 90.0f;
        Cheat::Aimbot::RecoilCompression = 0.5f;
        Cheat::Mode = "Aimbot";
        TCPLOGI("Aimbot ON (BulletTrack + MagicBullet forced OFF)");
    } else {
        Cheat::Aimbot::Enable = false;
        if (!Cheat::BulletTrack::Enable && !Cheat::Memory::MagicBullet) Cheat::Mode = "";
        TCPLOGI("Aimbot OFF");
    }
}

static void ApplyBulletTrack(bool on) {
    if (on) {
        Cheat::Aimbot::Enable            = false;
        Cheat::Memory::MagicBullet       = false;
        Cheat::BulletTrack::Enable       = true;
        Cheat::BulletTrack::Safe         = true;   // Safe BT always ON with BT
        Cheat::BulletTrack::VisCheck     = true;
        if (Cheat::BulletTrack::BulletRange <= 0.f) Cheat::BulletTrack::BulletRange = 150.0f;
        Cheat::Mode = "Safe BT";
        TCPLOGI("BulletTrack ON + Safe BT ON (Aimbot + MagicBullet forced OFF)");
    } else {
        Cheat::BulletTrack::Enable = false;
        Cheat::BulletTrack::Safe   = false;
        if (!Cheat::Aimbot::Enable && !Cheat::Memory::MagicBullet) Cheat::Mode = "";
        TCPLOGI("BulletTrack OFF + Safe BT OFF");
    }
}


static void ApplyEsp(bool on) {
    Cheat::Esp::Line     = on;
    Cheat::Esp::Skeleton = on;
    Cheat::Esp::Health   = on;
    Cheat::Esp::Distance = on;
    Cheat::Esp::Name     = false; // bot name never shown
    Cheat::Esp::Count    = on;
    TCPLOGI("ESP %s", on ? "ON" : "OFF");
}

// ── Parse one command ────────────────────────────────────────────────────────
static void HandleCommand(const std::string& raw, int clientFd) {
    std::string line;
    for (char c : raw) {
        if (c == '\r' || c == '\n') break;
        line += c;
    }
    if (line.empty()) return;

    if (line == "ping") {
        const char* pong = "pong\n";
        send(clientFd, pong, 5, MSG_NOSIGNAL);
        return;
    }

    auto eq = line.find('=');
    if (eq == std::string::npos) return;
    std::string key = line.substr(0, eq);
    std::string val = line.substr(eq + 1);
    bool on = (val == "1" || val == "true" || val == "on");

    if      (key == "aimbot")      ApplyAimbot(on);
    else if (key == "btrack")      ApplyBulletTrack(on);
    else if (key == "esp")      ApplyEsp(on);
    else if (key == "line")     { Cheat::Esp::Line = on;     TCPLOGI("Line %s", on?"ON":"OFF"); }
    else if (key == "skeleton") { Cheat::Esp::Skeleton = on; TCPLOGI("Skeleton %s", on?"ON":"OFF"); }
    else if (key == "health")   { Cheat::Esp::Health = on;   TCPLOGI("Health %s", on?"ON":"OFF"); }
    else if (key == "distance") { Cheat::Esp::Distance = on; TCPLOGI("Distance %s", on?"ON":"OFF"); }
    else if (key == "name")     { Cheat::Esp::Name = false; TCPLOGI("Name always OFF"); }
    else if (key == "count")    { Cheat::Esp::Count = on;    TCPLOGI("Count %s", on?"ON":"OFF"); }
    else if (key == "box")      { TCPLOGI("Box %s (no-op)", on?"ON":"OFF"); }
    else if (key == "items")    { TCPLOGI("Items %s (no-op)", on?"ON":"OFF"); }
    else if (key == "vehicle")  { Cheat::Esp::Vehicle::Name = on; TCPLOGI("Vehicle %s", on?"ON":"OFF"); }
    else if (key == "hideesp")      TCP_SetStreamMode(on);
    else if (key == "grenadewarn") { TCPLOGI("GrenadeWarning %s (no-op)", on?"ON":"OFF"); }
    else if (key == "wideview") { Cheat::Memory::WideView = on; TCPLOGI("WideView %s", on?"ON":"OFF"); }
    else if (key == "fps120")   { TCPLOGI("120FPS %s", on?"ON":"OFF"); }
    else if (key == "ignorebot")     { Cheat::Aimbot::IgnoreBot = on; Cheat::BulletTrack::IgnoreBot = on; TCPLOGI("IgnoreBot %s", on?"ON":"OFF"); }
    else if (key == "ignoreknocked") { Cheat::Aimbot::IgnoreKnock = on; Cheat::BulletTrack::IgnoreKnock = on; TCPLOGI("IgnoreKnock %s", on?"ON":"OFF"); }
    else if (key == "btrange")  { float v = atof(val.c_str()); if (v < 1) v = 1; if (v > 400) v = 400; Cheat::BulletTrack::BulletRange = v; Cheat::Aimbot::Radius = v; TCPLOGI("Range = %.0f", v); }
    else if (key == "aimbotrange") { float v = atof(val.c_str()); if (v < 10) v = 10; Cheat::Aimbot::Radius = v; TCPLOGI("AimbotRange = %.0f", v); }
    else if (key == "timefixer")    { TCPLOGI("TimeFixer %s (no-op)", on?"ON":"OFF"); }
    else if (key == "ipadrange")    { TCPLOGI("IpadRange %s (no-op)", val.c_str()); }
    else if (key == "changedeviceid") { TCPLOGI("ChangeDeviceId (no-op)"); }
    else TCPLOGE("Unknown key: %s", key.c_str());
}

// ── Per-client loop ──────────────────────────────────────────────────────────
static void ClientLoop(int clientFd) {
    char buf[512];
    std::string acc;
    while (true) {
        ssize_t n = recv(clientFd, buf, sizeof(buf) - 1, 0);
        if (n <= 0) break;
        buf[n] = '\0';
        acc += buf;
        size_t pos;
        while ((pos = acc.find('\n')) != std::string::npos) {
            std::string line = acc.substr(0, pos);
            acc.erase(0, pos + 1);
            HandleCommand(line, clientFd);
        }
        if (acc.size() > 4096) acc.clear();
    }
    close(clientFd);
    TCPLOGI("Client disconnected");
}

// ── Server accept loop ───────────────────────────────────────────────────────
static void ServerThread() {
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) { TCPLOGE("socket() fail"); return; }

    int opt = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(ARK_TCP_PORT);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");

    while (bind(srv, (sockaddr*)&addr, sizeof(addr)) < 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1000));
    }

    if (listen(srv, 4) < 0) { TCPLOGE("listen() fail"); close(srv); return; }
    TCPLOGI("TCP feature server on 127.0.0.1:%d", ARK_TCP_PORT);

    while (true) {
        int cli = accept(srv, nullptr, nullptr);
        if (cli < 0) { std::this_thread::sleep_for(std::chrono::milliseconds(200)); continue; }
        std::thread(ClientLoop, cli).detach();
    }

    close(srv);
}

// ── Public entry ─────────────────────────────────────────────────────────────
void StartTcpFeatureServer() {
    std::thread(ServerThread).detach();
}
