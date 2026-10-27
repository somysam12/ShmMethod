#pragma once

// ── Color shader (lines, filled rects, corner boxes) ─────────────────────────
static const char* VERT_COLOR_SRC = R"(
attribute vec2 aPos;
attribute vec4 aColor;
varying vec4 vColor;
uniform vec2 uScreenSize;
void main() {
    vec2 ndc = (aPos / uScreenSize) * 2.0 - 1.0;
    ndc.y = -ndc.y;
    gl_Position = vec4(ndc, 0.0, 1.0);
    vColor = aColor;
}
)";

static const char* FRAG_COLOR_SRC = R"(
precision mediump float;
varying vec4 vColor;
void main() {
    gl_FragColor = vColor;
}
)";

// ── Texture shader (font atlas text quads) ────────────────────────────────────
static const char* VERT_TEX_SRC = R"(
attribute vec2 aPos;
attribute vec2 aTexCoord;
attribute vec4 aColor;
varying vec2 vTex;
varying vec4 vColor;
uniform vec2 uScreenSize;
void main() {
    vec2 ndc = (aPos / uScreenSize) * 2.0 - 1.0;
    ndc.y = -ndc.y;
    gl_Position = vec4(ndc, 0.0, 1.0);
    vTex   = aTexCoord;
    vColor = aColor;
}
)";

static const char* FRAG_TEX_SRC = R"(
precision mediump float;
varying vec2 vTex;
varying vec4 vColor;
uniform sampler2D uTexture;
void main() {
    float alpha = texture2D(uTexture, vTex).a;
    gl_FragColor = vec4(vColor.rgb, vColor.a * alpha);
}
)";
