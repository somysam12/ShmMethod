#pragma once
// ═══════════════════════════════════════════════════════════════════════════
// StreamESP.h — HideESP / Stream Mode
// Same colors as canvas ESP. Rendered on eSkipScreenshot surface.
// ═══════════════════════════════════════════════════════════════════════════
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_activity.h>
#include <android/native_window.h>
#include <cmath>
#include <cstring>
#include <cstdio>
#include "ANwCreator.hpp"

static bool           g_StreamMode  = false;
static bool           s_streamReady = false;
static EGLDisplay     s_sDpy   = EGL_NO_DISPLAY;
static EGLSurface     s_sSurf  = EGL_NO_SURFACE;
static EGLContext     s_sCtx   = EGL_NO_CONTEXT;
static ANativeWindow* s_sWin   = nullptr;
static int            s_sW = 0, s_sH = 0;
static GLuint s_sProg = 0;
static GLint  s_sAPOS = -1, s_sUSCR = -1, s_sUCOL = -1;

static const char* S_VS = R"(
attribute vec2 aPos;
uniform vec2 uScreen;
void main(){
    vec2 n = (aPos / uScreen) * 2.0 - 1.0;
    n.y = -n.y;
    gl_Position = vec4(n, 0.0, 1.0);
})";
static const char* S_FS = R"(
precision mediump float;
uniform vec4 uColor;
void main(){ gl_FragColor = uColor; })";

static GLuint s_cmpSh(GLenum t,const char* src){
    GLuint s=glCreateShader(t);
    glShaderSource(s,1,&src,nullptr); glCompileShader(s);
    GLint ok=0; glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok){glDeleteShader(s);return 0;} return s;
}
static bool s_buildProg(){
    GLuint vs=s_cmpSh(GL_VERTEX_SHADER,S_VS);
    GLuint fs=s_cmpSh(GL_FRAGMENT_SHADER,S_FS);
    if(!vs||!fs) return false;
    s_sProg=glCreateProgram();
    glAttachShader(s_sProg,vs); glAttachShader(s_sProg,fs);
    glLinkProgram(s_sProg);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint ok=0; glGetProgramiv(s_sProg,GL_LINK_STATUS,&ok);
    if(!ok){glDeleteProgram(s_sProg);s_sProg=0;return false;}
    s_sAPOS=glGetAttribLocation(s_sProg,"aPos");
    s_sUSCR=glGetUniformLocation(s_sProg,"uScreen");
    s_sUCOL=glGetUniformLocation(s_sProg,"uColor");
    return true;
}
static void s_col(float r,float g,float b,float a=1.f){glUniform4f(s_sUCOL,r,g,b,a);}
static void s_line(float x0,float y0,float x1,float y1,float w=1.5f){
    glLineWidth(w);
    float v[4]={x0,y0,x1,y1};
    glVertexAttribPointer(s_sAPOS,2,GL_FLOAT,GL_FALSE,0,v);
    glEnableVertexAttribArray(s_sAPOS);
    glDrawArrays(GL_LINES,0,2);
}
static void s_rect(float l,float t,float r,float b){
    float v[8]={l,t,r,t,l,b,r,b};
    glVertexAttribPointer(s_sAPOS,2,GL_FLOAT,GL_FALSE,0,v);
    glEnableVertexAttribArray(s_sAPOS);
    glDrawArrays(GL_TRIANGLE_STRIP,0,4);
}
static void s_pill(float cx,float y,float w,float h,float r,float g,float b,float a){
    float cr=h*0.38f,x=cx-w*0.5f;
    s_col(r,g,b,a);
    s_rect(x+cr,y,x+w-cr,y+h);
    s_rect(x,y+cr*0.5f,x+cr,y+h-cr*0.5f);
    s_rect(x+w-cr,y+cr*0.5f,x+w,y+h-cr*0.5f);
}
static void s_pillBorder(float cx,float y,float w,float h,float r,float g,float b,float a){
    float cr=h*0.38f,x=cx-w*0.5f,lw=1.5f;
    s_col(r,g,b,a);
    s_line(x+cr,y,x+w-cr,y,lw);
    s_line(x+cr,y+h,x+w-cr,y+h,lw);
    s_line(x,y+cr*0.5f,x,y+h-cr*0.5f,lw);
    s_line(x+w,y+cr*0.5f,x+w,y+h-cr*0.5f,lw);
    s_line(x,y+cr*0.5f,x+cr,y,lw);
    s_line(x+w-cr,y,x+w,y+cr*0.5f,lw);
    s_line(x,y+h-cr*0.5f,x+cr,y+h,lw);
    s_line(x+w-cr,y+h,x+w,y+h-cr*0.5f,lw);
}

// 5×7 pixel font
static const uint8_t S_F5x7[][5]={
{0,0,0,0,0},{0,0,0x5F,0,0},{0,7,0,7,0},{0x14,0x7F,0x14,0x7F,0x14},
{0x24,0x2A,0x7F,0x2A,0x12},{0x23,0x13,8,0x64,0x62},{0x36,0x49,0x55,0x22,0x50},
{0,5,3,0,0},{0,0x1C,0x22,0x41,0},{0,0x41,0x22,0x1C,0},{0xA,4,0x1F,4,0xA},
{8,8,0x3E,8,8},{0,0x50,0x30,0,0},{8,8,8,8,8},{0,0x60,0x60,0,0},
{0x20,0x10,8,4,2},{0x3E,0x51,0x49,0x45,0x3E},{0,0x42,0x7F,0x40,0},
{0x42,0x61,0x51,0x49,0x46},{0x21,0x41,0x45,0x4B,0x31},{0x18,0x14,0x12,0x7F,0x10},
{0x27,0x45,0x45,0x45,0x39},{0x3C,0x4A,0x49,0x49,0x30},{1,0x71,9,5,3},
{0x36,0x49,0x49,0x49,0x36},{6,0x49,0x49,0x29,0x1E},{0,0x36,0x36,0,0},
{0,0x56,0x36,0,0},{8,0x14,0x22,0x41,0},{0x14,0x14,0x14,0x14,0x14},
{0,0x41,0x22,0x14,8},{2,1,0x51,9,6},{0x32,0x49,0x79,0x41,0x3E},
{0x7E,0x11,0x11,0x11,0x7E},{0x7F,0x49,0x49,0x49,0x36},{0x3E,0x41,0x41,0x41,0x22},
{0x7F,0x41,0x41,0x22,0x1C},{0x7F,0x49,0x49,0x49,0x41},{0x7F,9,9,9,1},
{0x3E,0x41,0x49,0x49,0x7A},{0x7F,8,8,8,0x7F},{0,0x41,0x7F,0x41,0},
{0x20,0x40,0x41,0x3F,1},{0x7F,8,0x14,0x22,0x41},{0x7F,0x40,0x40,0x40,0x40},
{0x7F,2,0xC,2,0x7F},{0x7F,4,8,0x10,0x7F},{0x3E,0x41,0x41,0x41,0x3E},
{0x7F,9,9,9,6},{0x3E,0x41,0x51,0x21,0x5E},{0x7F,9,0x19,0x29,0x46},
{0x46,0x49,0x49,0x49,0x31},{1,1,0x7F,1,1},{0x3F,0x40,0x40,0x40,0x3F},
{0x1F,0x20,0x40,0x20,0x1F},{0x3F,0x40,0x38,0x40,0x3F},{0x63,0x14,8,0x14,0x63},
{7,8,0x70,8,7},{0x61,0x51,0x49,0x45,0x43},{0,0x7F,0x41,0x41,0},
{2,4,8,0x10,0x20},{0,0x41,0x41,0x7F,0},{4,2,1,2,4},{0x40,0x40,0x40,0x40,0x40},
{0,1,2,4,0},{0x20,0x54,0x54,0x54,0x78},{0x7F,0x48,0x44,0x44,0x38},
{0x38,0x44,0x44,0x44,0x20},{0x38,0x44,0x44,0x48,0x7F},{0x38,0x54,0x54,0x54,0x18},
{8,0x7E,9,1,2},{0xC,0x52,0x52,0x52,0x3E},{0x7F,8,4,4,0x78},
{0,0x44,0x7D,0x40,0},{0x20,0x40,0x44,0x3D,0},{0x7F,0x10,0x28,0x44,0},
{0,0x41,0x7F,0x40,0},{0x7C,4,0x18,4,0x78},{0x7C,8,4,4,0x78},
{0x38,0x44,0x44,0x44,0x38},{0x7C,0x14,0x14,0x14,8},{8,0x14,0x14,0x18,0x7C},
{0x7C,8,4,4,8},{0x48,0x54,0x54,0x54,0x20},{4,0x3F,0x44,0x40,0x20},
{0x3C,0x40,0x40,0x20,0x7C},{0x1C,0x20,0x40,0x20,0x1C},{0x3C,0x40,0x30,0x40,0x3C},
{0x44,0x28,0x10,0x28,0x44},{0xC,0x50,0x50,0x50,0x3C},{0x44,0x64,0x54,0x4C,0x44},
{0,8,0x36,0x41,0},{0,0,0x7F,0,0},{0,0x41,0x36,8,0},{0xC,2,0xC,0x10,0xC},
};
static void s_drawTxt(const char* s,float px,float py,float sc){
    float cx=px;
    for(;*s;s++){
        unsigned char c=(unsigned char)*s;
        if(c<32||c>126)c=32;
        const uint8_t* g=S_F5x7[c-32];
        for(int col=0;col<5;col++){
            uint8_t cd=g[col];
            for(int row=0;row<7;row++){
                if(cd&(1<<row)){
                    float fx=cx+(float)col*sc, fy=py+(float)row*sc;
                    s_rect(fx,fy,fx+sc,fy+sc);
                }
            }
        }
        cx+=6.f*sc;
    }
}
static float s_txtW(const char* s,float sc){
    int n=0; for(const char*p=s;*p;p++)n++;
    return n*6.f*sc;
}

// ── Player data struct ─────────────────────────────────────────────────────
struct StreamPlayer {
    float headX,headY,footX,footY;
    float health,healthMax,distance;
    char  name[32];
    bool  hasLOS;
    bool  isBot;
    bool  isKnocked;
    float bones[16][2];
    bool  boneValid[16];
};

struct StreamVehicle {
    float scrX, scrY;
    float distance;
    char  name[32];
    bool  hasDriver;  // true = driven by enemy (red), false = empty (yellow)
};

// ── Init ───────────────────────────────────────────────────────────────────
static bool StreamESP_Init(ANativeActivity* act) {
    if (s_streamReady) return true;
    if (!act) return false;
    android::ANwCreator::CreateOptions opts;
    opts.name="KC_StreamESP"; opts.skipScreenshot=true;
    opts.width=-1; opts.height=-1;
    s_sWin=android::ANwCreator::Create(act,opts);
    if(!s_sWin){LOGI("StreamESP: Create failed");return false;}
    s_sDpy=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(s_sDpy,nullptr,nullptr);
    const EGLint ca[]={EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig cfg; EGLint nc=0;
    if(!eglChooseConfig(s_sDpy,ca,&cfg,1,&nc)||nc==0){
        eglTerminate(s_sDpy);s_sDpy=EGL_NO_DISPLAY;
        android::ANwCreator::Destroy(act,s_sWin);s_sWin=nullptr;return false;}
    const EGLint cxa[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    s_sCtx=eglCreateContext(s_sDpy,cfg,EGL_NO_CONTEXT,cxa);
    s_sSurf=eglCreateWindowSurface(s_sDpy,cfg,s_sWin,nullptr);
    if(s_sCtx==EGL_NO_CONTEXT||s_sSurf==EGL_NO_SURFACE){
        if(s_sSurf!=EGL_NO_SURFACE)eglDestroySurface(s_sDpy,s_sSurf);
        if(s_sCtx!=EGL_NO_CONTEXT)eglDestroyContext(s_sDpy,s_sCtx);
        eglTerminate(s_sDpy);s_sDpy=EGL_NO_DISPLAY;
        android::ANwCreator::Destroy(act,s_sWin);s_sWin=nullptr;return false;}
    eglMakeCurrent(s_sDpy,s_sSurf,s_sSurf,s_sCtx);
    eglQuerySurface(s_sDpy,s_sSurf,EGL_WIDTH,&s_sW);
    eglQuerySurface(s_sDpy,s_sSurf,EGL_HEIGHT,&s_sH);
    glViewport(0,0,s_sW,s_sH);
    glClearColor(0,0,0,0);
    s_buildProg();
    android::ANwCreator::EnsureVisible(act,s_sWin);
    eglMakeCurrent(EGL_NO_DISPLAY,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    s_streamReady=true;
    LOGI("✅ StreamESP ready (%dx%d)",s_sW,s_sH);
    return true;
}

// ── Draw frame — called from PostRender ────────────────────────────────────
static void StreamESP_DrawFrame(
    const StreamPlayer* players, int count,
    const StreamVehicle* vehicles, int vcount,
    int gameW, int gameH,
    bool drawLine, bool drawSkel, bool drawHealth,
    bool drawName, bool drawDist,
    int enemyCount, int botCount,
    const char* watermark, const char* expiry = nullptr)
{
    if(!s_streamReady||!s_sProg) return;

    EGLDisplay saveDpy=eglGetCurrentDisplay();
    EGLSurface saveDraw=eglGetCurrentSurface(EGL_DRAW);
    EGLSurface saveRead=eglGetCurrentSurface(EGL_READ);
    EGLContext saveCtx=eglGetCurrentContext();

    eglMakeCurrent(s_sDpy,s_sSurf,s_sSurf,s_sCtx);
    eglQuerySurface(s_sDpy,s_sSurf,EGL_WIDTH,&s_sW);
    eglQuerySurface(s_sDpy,s_sSurf,EGL_HEIGHT,&s_sH);
    glViewport(0,0,s_sW,s_sH);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
    glUseProgram(s_sProg);
    glUniform2f(s_sUSCR,(float)s_sW,(float)s_sH);

    float sx=(gameW>0)?(float)s_sW/(float)gameW:1.f;
    float sy=(gameH>0)?(float)s_sH/(float)gameH:1.f;

    // ── 1. Enemy Counter — metallic pill, screen mid-top ────────────────────
    const float CSC  = 2.2f;
    const float PILH = 30.f;
    const float PADX = 14.f;
    const float cth  = 7.f * CSC;
    const float PILY = 10.f;
    const float pillCX = (float)s_sW * 0.5f;
    float lineOriginX = pillCX;
    float lineOriginY = PILY + PILH + 6.f;

    if(enemyCount<=0 && botCount<=0){
        const char* clrStr="Clear";
        float ctw=s_txtW(clrStr,CSC), pillW=ctw+PADX*2.f;
        s_pill(pillCX,PILY,pillW,PILH,0.07f,0.20f,0.10f,0.90f);
        s_pill(pillCX,PILY,pillW,PILH*0.32f,0.30f,0.90f,0.40f,0.14f);
        s_pillBorder(pillCX,PILY,pillW,PILH,0.22f,0.78f,0.33f,0.60f);
        float tx=pillCX-ctw*0.5f, ty=PILY+(PILH-cth)*0.5f;
        s_col(0,0,0,0.80f); s_drawTxt(clrStr,tx+1,ty+1,CSC);
        s_col(0.42f,1.f,0.52f,1.f); s_drawTxt(clrStr,tx,ty,CSC);
    } else if(enemyCount>0 && botCount<=0){
        char cbuf[8]; snprintf(cbuf,sizeof(cbuf),"%02d",enemyCount);
        float ctw=s_txtW(cbuf,CSC), pillW=ctw+PADX*2.f;
        s_pill(pillCX,PILY,pillW,PILH,0.22f,0.06f,0.07f,0.90f);
        s_pill(pillCX,PILY,pillW,PILH*0.32f,1.f,0.30f,0.30f,0.12f);
        s_pillBorder(pillCX,PILY,pillW,PILH,0.85f,0.24f,0.26f,0.65f);
        float tx=pillCX-ctw*0.5f, ty=PILY+(PILH-cth)*0.5f;
        s_col(0,0,0,0.80f); s_drawTxt(cbuf,tx+1,ty+1,CSC);
        s_col(1.f,0.45f,0.45f,1.f); s_drawTxt(cbuf,tx,ty,CSC);
    } else if(enemyCount<=0 && botCount>0){
        char cbuf[8]; snprintf(cbuf,sizeof(cbuf),"%02d",botCount);
        float ctw=s_txtW(cbuf,CSC), pillW=ctw+PADX*2.f;
        s_pill(pillCX,PILY,pillW,PILH,0.20f,0.18f,0.04f,0.90f);
        s_pill(pillCX,PILY,pillW,PILH*0.32f,1.f,0.88f,0.20f,0.12f);
        s_pillBorder(pillCX,PILY,pillW,PILH,0.88f,0.78f,0.24f,0.65f);
        float tx=pillCX-ctw*0.5f, ty=PILY+(PILH-cth)*0.5f;
        s_col(0,0,0,0.80f); s_drawTxt(cbuf,tx+1,ty+1,CSC);
        s_col(1.f,0.90f,0.35f,1.f); s_drawTxt(cbuf,tx,ty,CSC);
    } else {
        char ebuf[8],bbuf[8];
        snprintf(ebuf,sizeof(ebuf),"%02d",enemyCount);
        snprintf(bbuf,sizeof(bbuf),"%02d",botCount);
        float etw=s_txtW(ebuf,CSC),btw=s_txtW(bbuf,CSC);
        float halfW=(etw>btw?etw:btw)+PADX*2.f;
        float pillW=halfW*2.f;
        float lx=pillCX-pillW*0.5f,cr2=PILH*0.38f;
        s_pill(pillCX,PILY,pillW,PILH,0.10f,0.10f,0.14f,0.92f);
        s_col(0.55f,0.06f,0.08f,0.48f);
        s_rect(lx+cr2,PILY,pillCX,PILY+PILH);
        s_rect(lx,PILY+cr2*0.5f,lx+cr2,PILY+PILH-cr2*0.5f);
        s_col(0.45f,0.38f,0.04f,0.48f);
        s_rect(pillCX,PILY,lx+pillW-cr2,PILY+PILH);
        s_rect(lx+pillW-cr2,PILY+cr2*0.5f,lx+pillW,PILY+PILH-cr2*0.5f);
        s_pill(pillCX,PILY,pillW,PILH*0.32f,0.70f,0.72f,0.80f,0.10f);
        s_pillBorder(pillCX,PILY,pillW,PILH,0.55f,0.58f,0.68f,0.55f);
        s_col(0.55f,0.58f,0.68f,0.50f);
        s_line(pillCX,PILY+3.f,pillCX,PILY+PILH-3.f,1.f);
        float tY=PILY+(PILH-cth)*0.5f;
        float etx=(lx+pillCX)*0.5f-etw*0.5f;
        float btx=(pillCX+lx+pillW)*0.5f-btw*0.5f;
        s_col(0,0,0,0.80f); s_drawTxt(ebuf,etx+1,tY+1,CSC);
        s_col(1.f,0.45f,0.45f,1.f); s_drawTxt(ebuf,etx,tY,CSC);
        s_col(0,0,0,0.80f); s_drawTxt(bbuf,btx+1,tY+1,CSC);
        s_col(1.f,0.90f,0.35f,1.f); s_drawTxt(bbuf,btx,tY,CSC);
    }

    // ── 2. Watermark — "ARK"=yellow "CHEAT"=orange "BGMI"=white, no overlap ─
    // watermark parameter is "ARK CHEAT BGMI" — split and colour each word
    if(watermark && watermark[0]){
        const float wsc=2.5f;
        // Hardcoded segments matching "ARK CHEAT BGMI"
        const char* w1="ARK"; const char* w2=" CHEAT"; const char* w3=" BGMI";
        float tw1=s_txtW(w1,wsc), tw2=s_txtW(w2,wsc), tw3=s_txtW(w3,wsc);
        float totalW=tw1+tw2+tw3, th=7.f*wsc;
        float wx=(float)s_sW-totalW-80.f, wy=(float)s_sH-th-18.f;
        // Dark background for full watermark
        s_col(0,0,0,0.75f); s_rect(wx-6,wy-4,wx+totalW+6,wy+th+4);
        // Shadows
        s_col(0,0,0,1.f); s_drawTxt(w1,wx+1,wy+1,wsc);
        s_col(0,0,0,1.f); s_drawTxt(w2,wx+tw1+1,wy+1,wsc);
        s_col(0,0,0,1.f); s_drawTxt(w3,wx+tw1+tw2+1,wy+1,wsc);
        // Colours: ARK=yellow, CHEAT=orange, BGMI=white
        s_col(1.f,0.86f,0.f,1.f); s_drawTxt(w1,wx,wy,wsc);
        s_col(1.f,0.47f,0.f,1.f); s_drawTxt(w2,wx+tw1,wy,wsc);
        s_col(1.f,1.f,1.f,1.f);   s_drawTxt(w3,wx+tw1+tw2,wy,wsc);
    }

    // ── 2b. Expiry (bottom left) — red, no edge cutout ─────────────────────
    //  ex=90 keeps it clear of rounded corner cutout in landscape.
    //  Expiry string already formatted as "ACCESS VALIDITY X DAYS" by KC_BuildExpiryStr.
    if(expiry && expiry[0]){
        const float esc=3.2f;
        float tw=s_txtW(expiry,esc), th=7.f*esc;
        float ex=90.f, ey=(float)s_sH-th-22.f;  // 90px from left — past rounded corner
        s_col(0,0,0,0.75f); s_rect(ex-6,ey-4,ex+tw+6,ey+th+4);
        s_col(0,0,0,1.f);       s_drawTxt(expiry,ex+1,ey+1,esc);
        s_col(1.f,0.2f,0.2f,1.f); s_drawTxt(expiry,ex,ey,esc);    // red
    }

    // ── 3. Vehicles ──────────────────────────────────────────────────────
    for(int i=0;i<vcount;i++){
        const StreamVehicle& v=vehicles[i];
        float vx=v.scrX*sx, vy=v.scrY*sy;
        const float vsc=2.5f;
        // name + distance text
        char vbuf[48];
        snprintf(vbuf,sizeof(vbuf),"%s(%dm)",v.name,(int)v.distance);
        float tw=s_txtW(vbuf,vsc), th=7.f*vsc;
        float nx=vx-tw*0.5f, ny=vy-th-4.f;
        s_col(0.04f,0.04f,0.06f,0.8f); s_rect(nx-3,ny-2,nx+tw+3,ny+th+2);
        // yellow = empty, red = enemy inside
        if(v.hasDriver) s_col(1.f,0.f,0.f,1.f);   // red — enemy driving
        else             s_col(1.f,1.f,0.f,1.f);   // yellow — empty
        s_col(0,0,0,0.9f); s_drawTxt(vbuf,nx+1,ny+1,vsc);
        if(v.hasDriver) s_col(1.f,0.15f,0.15f,1.f);
        else            s_col(1.f,1.f,0.f,1.f);
        s_drawTxt(vbuf,nx,ny,vsc);
    }

    // ── 4. Players — FIXED-SIZE ESP (0% distance scaling) ────────────────
    // All elements (box, skeleton, health bar, text) are a constant pixel
    // size regardless of how far the player is.  Only the screen POSITION
    // of the box changes with distance via W2S projection.
    for(int i=0;i<count;i++){
        const StreamPlayer& e=players[i];
        float hx=e.headX*sx, hy=e.headY*sy;

        float cr,cg,cb2;
        if(e.hasLOS){ cr=0.f; cg=1.f; cb2=0.f; }  // green — visible
        else         { cr=1.f; cg=0.f; cb2=0.f; }  // red   — in cover

        // Fixed box — same pixel size at every distance.
        // Anchored at head screen position; ignores foot position for sizing.
        const float FBW = 44.f;
        const float FBH = 72.f;
        float bL = hx - FBW * 0.5f;
        float bR = hx + FBW * 0.5f;
        float bT = hy - 2.f;
        float bB = bT + FBH;
        float bCX = hx;

        // ESP Line — counter → box top
        if(drawLine){
            s_col(cr,cg,cb2,1.f);
            s_line(lineOriginX,lineOriginY, bCX, bT, 3.5f);
        }

        // Skeleton — remapped into fixed box so it never scales with distance.
        // Step 1: find bounding rect of valid W2S bone positions.
        // Step 2: linearly remap each valid bone into [bL,bR]×[bT,bB].
        if(drawSkel){
            float minBX=1e30f,maxBX=-1e30f,minBY=1e30f,maxBY=-1e30f;
            bool anyBone=false;
            for(int b=0;b<16;b++){
                if(!e.boneValid[b]) continue;
                float bxr=e.bones[b][0]*sx, byr=e.bones[b][1]*sy;
                if(bxr<minBX)minBX=bxr; if(bxr>maxBX)maxBX=bxr;
                if(byr<minBY)minBY=byr; if(byr>maxBY)maxBY=byr;
                anyBone=true;
            }
            if(anyBone){
                float rX=std::max(maxBX-minBX,1.f);
                float rY=std::max(maxBY-minBY,1.f);
                float fbx[16]={},fby[16]={};
                for(int b=0;b<16;b++){
                    if(!e.boneValid[b]) continue;
                    fbx[b]=bL+FBW*(e.bones[b][0]*sx-minBX)/rX;
                    fby[b]=bT+FBH*(e.bones[b][1]*sy-minBY)/rY;
                }
                s_col(cr,cg,cb2,0.9f);
                auto bl=[&](int a,int b){
                    if(!e.boneValid[a]||!e.boneValid[b]) return;
                    s_line(fbx[a],fby[a],fbx[b],fby[b],1.3f);
                };
                // Spine
                bl(0,1);bl(1,2);bl(2,3);bl(3,4);bl(4,5);
                // Right arm
                bl(1,6);bl(6,7);bl(7,8);
                // Left arm
                bl(1,9);bl(9,10);bl(10,11);
            }
            // Head dot — fixed 6×6 px
            s_col(cr,cg,cb2,1.f); s_rect(hx-3.f,hy-3.f,hx+3.f,hy+3.f);
            // Corner box — fixed FBW×FBH px
            float cLen=FBW*0.25f;
            s_col(cr,cg,cb2,1.f);
            s_line(bL,bT,bL+cLen,bT,2.f); s_line(bL,bT,bL,bT+cLen,2.f);
            s_line(bR-cLen,bT,bR,bT,2.f); s_line(bR,bT,bR,bT+cLen,2.f);
            s_line(bL,bB-cLen,bL,bB,2.f); s_line(bL,bB,bL+cLen,bB,2.f);
            s_line(bR-cLen,bB,bR,bB,2.f); s_line(bR,bB,bR,bB-cLen,2.f);
        }

        // Health bar — fixed width (FBW px), fixed height (4 px), above box top
        if(drawHealth && e.healthMax>0.f){
            float hp=std::max(0.f,std::min(e.health/e.healthMax,1.f));
            float hbT=bT-8.f, hbB=bT-4.f;
            s_col(0.1f,0.1f,0.1f,0.7f); s_rect(bL,hbT,bR,hbB);
            float hr,hg,hb2;
            if(e.isKnocked){ hr=1.f; hg=0.f; hb2=0.f; }
            else            { hr=0.f; hg=1.f; hb2=0.f; }
            s_col(hr,hg,hb2,0.95f); s_rect(bL,hbT,bL+FBW*hp,hbB);
        }

        // Distance — below fixed box bottom (NOT below W2S foot)
        if(drawDist){
            char db[16]; snprintf(db,sizeof(db),"%dm",(int)e.distance);
            const float dsc=1.6f;
            float tw=s_txtW(db,dsc),th=7.f*dsc;
            float dx=bCX-tw*0.5f, dy=bB+4.f;
            s_col(0.05f,0.05f,0.07f,0.82f); s_rect(dx-3,dy-2,dx+tw+3,dy+th+2);
            s_col(0,0,0,0.9f); s_drawTxt(db,dx+1,dy+1,dsc);
            s_col(cr,cg,cb2,1.f); s_drawTxt(db,dx,dy,dsc);
        }

        // Name — below distance (below fixed box bottom)
        if(drawName){
            const char* nm=e.isBot?"Bot":e.name;
            const float nsc=1.6f;
            float tw=s_txtW(nm,nsc),th=7.f*nsc;
            float nx=bCX-tw*0.5f, ny=bB+4.f+(drawDist?(7.f*1.6f+8.f):0.f);
            s_col(0.04f,0.04f,0.06f,0.82f); s_rect(nx-3,ny-2,nx+tw+3,ny+th+2);
            s_col(0,0,0,0.9f); s_drawTxt(nm,nx+1,ny+1,nsc);
            if(e.isBot) s_col(1.f,1.f,0.f,1.f);
            else        s_col(0.f,1.f,1.f,1.f);
            s_drawTxt(nm,nx,ny,nsc);
        }
    }

    eglSwapBuffers(s_sDpy,s_sSurf);
    eglMakeCurrent(saveDpy,saveDraw,saveRead,saveCtx);
}

// ── Clear the hidden stream surface ONCE (blank it) ──────────────────────────
// Call this when HideESP turns OFF, so the last hidden-ESP frame doesn't stay
// stuck on the eSkipScreenshot surface next to the normal (canvas) ESP.
// Uses the SAME save/restore context pattern as StreamESP_DrawFrame (safe).
static void StreamESP_ClearOnce() {
    if(!s_streamReady) return;

    EGLDisplay saveDpy=eglGetCurrentDisplay();
    EGLSurface saveDraw=eglGetCurrentSurface(EGL_DRAW);
    EGLSurface saveRead=eglGetCurrentSurface(EGL_READ);
    EGLContext saveCtx=eglGetCurrentContext();

    eglMakeCurrent(s_sDpy,s_sSurf,s_sSurf,s_sCtx);
    eglQuerySurface(s_sDpy,s_sSurf,EGL_WIDTH,&s_sW);
    eglQuerySurface(s_sDpy,s_sSurf,EGL_HEIGHT,&s_sH);
    glViewport(0,0,s_sW,s_sH);
    glClearColor(0.f,0.f,0.f,0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(s_sDpy,s_sSurf);

    eglMakeCurrent(saveDpy,saveDraw,saveRead,saveCtx);
}
