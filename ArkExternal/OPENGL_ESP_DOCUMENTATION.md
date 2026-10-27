# ArkExternal — OpenGL ESP Renderer Documentation
## librender.so (EGL/OpenGL ES 2.0)

---

## Overview

This document explains the complete architecture of the OpenGL-based ESP overlay, from basic concepts to full implementation details.

---

## 1. Architecture — How It All Connects

```
┌─────────────────────────────────────────────────────────────────┐
│  BGMI Game Process                                              │
│  ┌───────────────┐                                              │
│  │  libmain.so   │  (injected)                                  │
│  │  (Shm.zip)    │──► writes enemy positions, health,           │
│  │               │    names, bones → SHM file                   │
│  └───────────────┘                                              │
└─────────────────────────────────────────────────────────────────┘
              │
              ▼  (shared memory file on disk)
    /storage/emulated/0/CrashLog/ark_esp_v1
              │
              ▼
┌─────────────────────────────────────────────────────────────────┐
│  ArkExternal APK Process                                        │
│                                                                  │
│  EspOverlay.java (Service)                                      │
│   │ creates SurfaceView → adds to WindowManager overlay         │
│   │ calls nativeInit(surface, w, h)                             │
│   │ generates font atlas bitmap via Canvas                       │
│   │ calls nativeSetFont(...) — uploads to GPU texture           │
│   │ calls nativeStartRender() — starts C++ render thread        │
│   └── calls nativeSetFlags() when toggles change                │
│                                                                  │
│  librender.so (C++ OpenGL renderer)                             │
│   │ EGL: creates OpenGL ES 2.0 context on SurfaceView           │
│   │ Render thread (~60fps):                                      │
│   │   1. Read SHM → parse enemy/vehicle data                    │
│   │   2. Reset vertex batch                                      │
│   │   3. drawFrame() → drawPlayer() × N, vehicles, HUD          │
│   │   4. flush VBOs → GPU draw calls                            │
│   │   5. eglSwapBuffers → frame shown on screen                 │
│   └──────────────────────────────────────────────────────────── │
│                                                                  │
│  libshaitaan.so (unchanged)                                     │
│   └── handles Downtwo download, LoginActivity, HeartbeatManager │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. SHM (Shared Memory) Protocol

### File paths (tried in order):
1. `/storage/emulated/0/CrashLog/ark_esp_v1`
2. `/data/local/tmp/ark_esp_v1`

### Data layout (binary, little-endian):

```
Offset 0    → EspFrameHeader (32 bytes)
  [0]  uint32  seqWrite      — seqlock counter (odd=writing, even=done)
  [4]  uint32  reserved
  [8]  float   reserved
  [12] float   reserved
  [16] int32   playerCount   — number of players in array
  [20] int32   vehCount      — number of vehicles in array
  [24] int32   enemyCount    — enemy count (for panel display)
  [28] int32   botCount      — bot count

Offset 32   → EspPlayer[100] (100 × 216 bytes = 21600 bytes)
  Each EspPlayer (216 bytes):
    [0]   float  headX, headY   — normalized 0.0..1.0 screen position
    [8]   float  rootX, rootY   — foot position (normalized)
    [16]  float  boneX[17]      — 17 bone X positions (normalized)
    [84]  float  boneY[17]      — 17 bone Y positions (normalized)
    [152] uint8  boneVis[17]    — 1=bone visible, 0=not
    [169] uint8  pad[3]         — alignment padding
    [172] float  health         — 0.0..1.0
    [176] float  distance       — metres
    [180] uint8  isBot
    [181] uint8  isVis          — 1=enemy is visible to player
    [182] uint8  isKnk          — 1=knocked/downed
    [183] uint8  hasArmor
    [184] char   name[32]       — null-terminated name string

Offset 21632 → EspVehicle[20] (20 × 48 bytes = 960 bytes)
  Each EspVehicle (48 bytes):
    [0]  float  x, y            — normalized screen position
    [8]  float  dist            — metres
    [12] uint8  hasEnemy        — 1=has enemy inside
    [13] uint8  pad[3]
    [16] char   name[32]

Offset 22592 → int32 fps
Offset 22596 → char expiry[48]
```

### Seqlock read pattern (C++):
```cpp
uint32_t s1, s2;
do {
    s1 = frame->seqWrite;
    if (s1 & 1u) continue;          // odd = write in progress, spin
    memcpy(&localCopy, frame, sizeof(EspFrame));
    s2 = frame->seqWrite;
} while (s1 != s2);                 // changed during read, retry
// localCopy is now consistent
```

---

## 3. EGL / OpenGL Setup

### EGL initialization (inside render thread for thread affinity):
```cpp
EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
eglInitialize(dpy, &maj, &min);

EGLint attribs[] = {
    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
    EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
    EGL_RED_SIZE,   8, EGL_GREEN_SIZE, 8,
    EGL_BLUE_SIZE,  8, EGL_ALPHA_SIZE, 8,
    EGL_NONE
};
EGLConfig cfg; EGLint n;
eglChooseConfig(dpy, attribs, &cfg, 1, &n);

EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttr);

// ANativeWindow comes from Java Surface passed to nativeInit()
EGLSurface surf = eglCreateWindowSurface(dpy, cfg, nativeWindow, nullptr);
eglMakeCurrent(dpy, surf, surf, ctx);

// Enable alpha blending for transparent overlay
glEnable(GL_BLEND);
glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
glDisable(GL_DEPTH_TEST);
```

### Why RGBA_8888 + alpha blending?
The SurfaceView uses `PixelFormat.RGBA_8888`. Setting `glClearColor(0,0,0,0)` makes the background fully transparent. Only the ESP drawings (boxes, lines, text) appear on screen — everything else is see-through.

---

## 4. Shaders (GLSL ES 1.00)

### Color shader — for lines, rectangles, boxes:
```glsl
// Vertex
attribute vec2 aPos;
attribute vec4 aColor;
varying vec4 vColor;
uniform vec2 uScreenSize;
void main() {
    vec2 ndc = (aPos / uScreenSize) * 2.0 - 1.0;
    ndc.y = -ndc.y;  // flip Y: screen top = NDC top
    gl_Position = vec4(ndc, 0.0, 1.0);
    vColor = aColor;
}

// Fragment
precision mediump float;
varying vec4 vColor;
void main() { gl_FragColor = vColor; }
```

### Texture shader — for font atlas text:
```glsl
// Vertex (adds UV coords)
attribute vec2 aPos; attribute vec2 aTexCoord; attribute vec4 aColor;
varying vec2 vTex; varying vec4 vColor;
uniform vec2 uScreenSize;
void main() {
    vec2 ndc = (aPos / uScreenSize) * 2.0 - 1.0; ndc.y = -ndc.y;
    gl_Position = vec4(ndc, 0.0, 1.0);
    vTex = aTexCoord; vColor = aColor;
}

// Fragment (multiplies texture alpha by vertex color alpha)
precision mediump float;
varying vec2 vTex; varying vec4 vColor;
uniform sampler2D uTexture;
void main() {
    float alpha = texture2D(uTexture, vTex).a;
    gl_FragColor = vec4(vColor.rgb, vColor.a * alpha);
}
```

---

## 5. GlBatch — Dynamic Vertex Buffer

Every frame all geometry is rebuilt from scratch into 3 CPU-side vectors:
- `_lines` — pairs of ColorVertex for `GL_LINES`
- `_tris` — triplets of ColorVertex for `GL_TRIANGLES` (filled rects)
- `_texTris` — triplets of TexVertex for `GL_TRIANGLES` (text glyphs)

At `flush()` time, each vector is uploaded to its VBO and drawn in one call.

```
ColorVertex: { float x, y, r, g, b, a }     // 24 bytes
TexVertex:   { float x, y, u, v, r, g, b, a } // 32 bytes
```

This batching means the GPU gets at most 3 draw calls per frame regardless of how many enemies are on screen.

---

## 6. BlueFox ESP Style

### Corner box (no full outline, just L-shaped corners):
```
┌─         ─┐
│           │
│           │
└─         ─┘
```
Each corner = 2 line segments (horizontal + vertical) whose length = 25% of min(width, height).

### Glow effect (3 layered outer rings):
```cpp
// 3 expanding rings with decreasing alpha
cornerBox(x1-3,y1-3, x2+3,y2+3, r,g,b, 0.08f);  // very faint
cornerBox(x1-2,y1-2, x2+2,y2+2, r,g,b, 0.15f);  // faint
cornerBox(x1-1,y1-1, x2+1,y2+1, r,g,b, 0.25f);  // light
cornerBox(x1,  y1,   x2,  y2,   r,g,b, 1.0f);   // main box
```

### Color scheme:
| State | Hex | RGB |
|-------|-----|-----|
| Visible enemy | `#A4FF73` | Lime green |
| Hidden enemy  | `#FF4444` | Red |
| Knocked       | `#FF3333` | Bright red |
| Bot           | `#FFC96B` | Orange |

### Proportional box sizing:
```cpp
float rawH  = (rootY - headY) * screenH;   // head-to-foot px distance
float boxH  = max(rawH, 16f);               // min 16px for tiny/far enemies
float boxW  = boxH * 0.52f;                 // human aspect ratio
```
This naturally scales: close enemy = large box, distant enemy = small box.

### Health bar — gradient:
```
[━━━━━━━━━] 100%  → Green
[━━━━━━   ]  60%  → Yellow-green
[━━━      ]  30%  → Yellow
[━        ]  10%  → Red
```

### ESP lines:
- Origin: bottom of the center-top enemy counter panel
- Destination: enemy head position (`headX, headY`)
- Visible enemy: lime green at 51% alpha
- Hidden enemy: grey at 31% alpha

---

## 7. Font Atlas

### Generation (Java side, one-time at startup):
1. Create 512×64 Bitmap with `Bitmap.Config.ARGB_8888`
2. Draw all 96 printable ASCII chars (space through `~`) using `Canvas.drawText()` with bold typeface
3. Record each char's pixel width and x-offset in atlas
4. Copy bitmap pixels to `byte[]` → pass to `nativeSetFont()`

### GPU usage (C++ side):
1. Receive RGBA bytes → upload as `GL_RGBA` texture
2. Store `CharInfo` for each char: UV coordinates + advance width
3. To draw text: emit one `texQuad` per character, advancing X by char advance
4. Fragment shader multiplies texture alpha → colored text with proper anti-aliasing

---

## 8. File Structure

```
app/src/main/jni/
├── main.cpp              — libshaitaan.so (Downtwo/Login/Heartbeat JNI)
├── Android.mk            — builds both libshaitaan.so and librender.so
├── EspSharedMem.h        — SHM writer (used by injected libmain.so)
└── opengl/
    ├── shaders.h         — GLSL source strings
    ├── gl_batch.h        — vertex batch manager (header-only)
    ├── font_atlas.h      — GPU font atlas (header-only)
    ├── esp_renderer.h    — EspRenderer class declaration + SHM structs
    ├── esp_renderer.cpp  — EGL init, render loop, ESP draw logic
    └── jni_render.cpp    — JNI exports (Java_com_bgmi_EspOverlay_native*)

app/src/main/java/com/bgmi/
└── EspOverlay.java       — Android Service: SurfaceView + font atlas + JNI calls
```

---

## 9. JNI Bridge

| Java method | C++ function | Purpose |
|-------------|--------------|---------|
| `nativeInit(Surface, w, h)` | `Java_com_bgmi_EspOverlay_nativeInit` | Get ANativeWindow, store dimensions |
| `nativeSetFont(...)` | `Java_com_bgmi_EspOverlay_nativeSetFont` | Upload font atlas texture data |
| `nativeStartRender()` | `Java_com_bgmi_EspOverlay_nativeStartRender` | Start EGL + render thread |
| `nativeSetFlags(...)` | `Java_com_bgmi_EspOverlay_nativeSetFlags` | Toggle ESP features |
| `nativeStop()` | `Java_com_bgmi_EspOverlay_nativeStop` | Stop thread, destroy EGL |

---

## 10. Performance vs Java Canvas

| Metric | Java Canvas | OpenGL librender.so |
|--------|-------------|---------------------|
| Rendering | CPU software rasterizer | GPU hardware accelerated |
| FPS ceiling | ~30fps | 60fps+ |
| JVM GC pauses | Yes (causes frame drops) | None |
| Memory | Java heap allocations/frame | Pre-allocated VBOs |
| Decompilation | Fully visible via jadx | Obfuscated in .so |
| Glow effects | Not possible | Native GPU multi-pass |
| Gradient health bar | Flat color only | Per-vertex color interp |
| Custom fonts | Limited | Any TTF via atlas |
| Detection surface | Java overlay easily detected | Harder to detect |

---

## 11. Changing ESP Features

To enable/disable features from OverlayService panel toggles:
```java
// These setter methods update the static field AND push flags to native
EspOverlay.setDrawLine(true);
EspOverlay.setDrawBox(true);      // enables corner box + glow
EspOverlay.setDrawSkeleton(true); // enables bone skeleton
EspOverlay.setDrawHealth(true);
EspOverlay.setDrawName(true);
EspOverlay.setDrawDistance(true);
EspOverlay.setDrawCount(true);    // enemy counter panel
EspOverlay.setDrawVehicle(true);
```

Direct field assignment (e.g. `EspOverlay.drawLine = true`) does NOT push to native — always use the setter methods.

---

## 12. Adding New ESP Elements

To add a new visual element (e.g. loot markers, airdrop circles):

1. Add SHM struct field in `esp_renderer.h`
2. Parse it in `drawFrame()` inside `esp_renderer.cpp`
3. Use `_batch.line()`, `_batch.fillRect()`, `_batch.cornerBox()` to draw
4. Add feature flag: `std::atomic<bool> _fNewFeature{false}` in `EspRenderer`
5. Add to `setFlags()` parameter list
6. Add `nativeSetFlags` JNI call in `jni_render.cpp`
7. Add Java field + setter in `EspOverlay.java`
8. Add toggle in `OverlayService.java`

---

*End of documentation*
