#pragma once
#include <GLES2/gl2.h>
#include <vector>
#include <cstring>
#include "shaders.h"

struct ColorVertex { float x, y, r, g, b, a; };
struct TexVertex   { float x, y, u, v, r, g, b, a; };

class GlBatch {
public:
    void init() {
        if (_cvbo == 0) glGenBuffers(1, &_cvbo);
        if (_tvbo == 0) glGenBuffers(1, &_tvbo);
    }

    void reset() {
        _lines.clear();
        _tris.clear();
        _texTris.clear();
    }

    // ── Line ──────────────────────────────────────────────────────────────────
    // OpenGL GL_LINES uses line width — set via glLineWidth before draw
    void line(float x1, float y1, float x2, float y2,
              float r, float g, float b, float a, float /*w*/ = 1.5f) {
        _lines.push_back({x1, y1, r, g, b, a});
        _lines.push_back({x2, y2, r, g, b, a});
    }

    // ── Filled rect ───────────────────────────────────────────────────────────
    void fillRect(float x1, float y1, float x2, float y2,
                  float r, float g, float b, float a) {
        // 2 triangles (CCW)
        _tris.push_back({x1, y1, r, g, b, a});
        _tris.push_back({x2, y1, r, g, b, a});
        _tris.push_back({x1, y2, r, g, b, a});

        _tris.push_back({x2, y1, r, g, b, a});
        _tris.push_back({x2, y2, r, g, b, a});
        _tris.push_back({x1, y2, r, g, b, a});
    }

    // ── Gradient filled rect (left→right color) ───────────────────────────────
    void fillRectGrad(float x1, float y1, float x2, float y2,
                      float r0, float g0, float b0,
                      float r1, float g1, float b1) {
        _tris.push_back({x1, y1, r0, g0, b0, 1.f});
        _tris.push_back({x2, y1, r1, g1, b1, 1.f});
        _tris.push_back({x1, y2, r0, g0, b0, 1.f});

        _tris.push_back({x2, y1, r1, g1, b1, 1.f});
        _tris.push_back({x2, y2, r1, g1, b1, 1.f});
        _tris.push_back({x1, y2, r0, g0, b0, 1.f});
    }

    // ── Corner box (BlueFox L-shaped corners, no full rect) ───────────────────
    void cornerBox(float x1, float y1, float x2, float y2,
                   float r, float g, float b, float a, float /*thick*/ = 1.5f) {
        float w = x2 - x1;
        float h = y2 - y1;
        float minDim = w < h ? w : h;
        float cx = minDim * 0.25f;
        float cy = minDim * 0.25f;

        // Top-left
        line(x1, y1, x1 + cx, y1,   r, g, b, a);
        line(x1, y1, x1,      y1 + cy, r, g, b, a);
        // Top-right
        line(x2 - cx, y1, x2, y1,   r, g, b, a);
        line(x2, y1,      x2, y1 + cy, r, g, b, a);
        // Bottom-left
        line(x1, y2 - cy, x1, y2,   r, g, b, a);
        line(x1, y2,      x1 + cx, y2, r, g, b, a);
        // Bottom-right
        line(x2, y2 - cy, x2, y2,   r, g, b, a);
        line(x2 - cx, y2, x2, y2,   r, g, b, a);
    }

    // ── Glow box (3 faint outer layers + main cornerBox) ─────────────────────
    void glowBox(float x1, float y1, float x2, float y2,
                 float r, float g, float b) {
        cornerBox(x1-3.f, y1-3.f, x2+3.f, y2+3.f, r, g, b, 0.08f, 3.f);
        cornerBox(x1-2.f, y1-2.f, x2+2.f, y2+2.f, r, g, b, 0.15f, 2.5f);
        cornerBox(x1-1.f, y1-1.f, x2+1.f, y2+1.f, r, g, b, 0.25f, 2.f);
        cornerBox(x1,     y1,     x2,     y2,      r, g, b, 1.0f,  1.5f);
    }

    // ── Textured quad (for font glyph) ────────────────────────────────────────
    void texQuad(float x, float y, float w, float h,
                 float u0, float v0, float u1, float v1,
                 float r, float g, float b, float a) {
        _texTris.push_back({x,     y,     u0, v0, r, g, b, a});
        _texTris.push_back({x + w, y,     u1, v0, r, g, b, a});
        _texTris.push_back({x,     y + h, u0, v1, r, g, b, a});

        _texTris.push_back({x + w, y,     u1, v0, r, g, b, a});
        _texTris.push_back({x + w, y + h, u1, v1, r, g, b, a});
        _texTris.push_back({x,     y + h, u0, v1, r, g, b, a});
    }

    // ── Flush: upload + draw all accumulated geometry ─────────────────────────
    void flush(GLuint colorProg, GLuint texProg, float screenW, float screenH) {
        if (colorProg == 0 && texProg == 0) return;

        // ── Color program (lines + tris) ────────────────────────────────────
        if (colorProg != 0 && (!_lines.empty() || !_tris.empty())) {
            glUseProgram(colorProg);
            GLint uSz   = glGetUniformLocation(colorProg, "uScreenSize");
            GLint aPos  = glGetAttribLocation(colorProg,  "aPos");
            GLint aCol  = glGetAttribLocation(colorProg,  "aColor");
            glUniform2f(uSz, screenW, screenH);

            glBindBuffer(GL_ARRAY_BUFFER, _cvbo);

            // Lines
            if (!_lines.empty()) {
                glBufferData(GL_ARRAY_BUFFER,
                    (GLsizeiptr)(_lines.size() * sizeof(ColorVertex)),
                    _lines.data(), GL_DYNAMIC_DRAW);
                glEnableVertexAttribArray(aPos);
                glEnableVertexAttribArray(aCol);
                glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE,
                    sizeof(ColorVertex), (void*)0);
                glVertexAttribPointer(aCol, 4, GL_FLOAT, GL_FALSE,
                    sizeof(ColorVertex), (void*)(2 * sizeof(float)));
                glLineWidth(1.5f);
                glDrawArrays(GL_LINES, 0, (GLsizei)_lines.size());
            }

            // Filled tris
            if (!_tris.empty()) {
                glBufferData(GL_ARRAY_BUFFER,
                    (GLsizeiptr)(_tris.size() * sizeof(ColorVertex)),
                    _tris.data(), GL_DYNAMIC_DRAW);
                glEnableVertexAttribArray(aPos);
                glEnableVertexAttribArray(aCol);
                glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE,
                    sizeof(ColorVertex), (void*)0);
                glVertexAttribPointer(aCol, 4, GL_FLOAT, GL_FALSE,
                    sizeof(ColorVertex), (void*)(2 * sizeof(float)));
                glDrawArrays(GL_TRIANGLES, 0, (GLsizei)_tris.size());
            }

            glDisableVertexAttribArray(aPos);
            glDisableVertexAttribArray(aCol);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }

        // ── Texture program (text quads) ────────────────────────────────────
        if (texProg != 0 && !_texTris.empty()) {
            glUseProgram(texProg);
            GLint uSz   = glGetUniformLocation(texProg, "uScreenSize");
            GLint uTex  = glGetUniformLocation(texProg, "uTexture");
            GLint aPos  = glGetAttribLocation(texProg,  "aPos");
            GLint aTex  = glGetAttribLocation(texProg,  "aTexCoord");
            GLint aCol  = glGetAttribLocation(texProg,  "aColor");
            glUniform2f(uSz, screenW, screenH);
            glUniform1i(uTex, 0);

            glBindBuffer(GL_ARRAY_BUFFER, _tvbo);
            glBufferData(GL_ARRAY_BUFFER,
                (GLsizeiptr)(_texTris.size() * sizeof(TexVertex)),
                _texTris.data(), GL_DYNAMIC_DRAW);

            glEnableVertexAttribArray(aPos);
            glEnableVertexAttribArray(aTex);
            glEnableVertexAttribArray(aCol);
            glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE,
                sizeof(TexVertex), (void*)0);
            glVertexAttribPointer(aTex, 2, GL_FLOAT, GL_FALSE,
                sizeof(TexVertex), (void*)(2 * sizeof(float)));
            glVertexAttribPointer(aCol, 4, GL_FLOAT, GL_FALSE,
                sizeof(TexVertex), (void*)(4 * sizeof(float)));

            glDrawArrays(GL_TRIANGLES, 0, (GLsizei)_texTris.size());

            glDisableVertexAttribArray(aPos);
            glDisableVertexAttribArray(aTex);
            glDisableVertexAttribArray(aCol);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }

private:
    std::vector<ColorVertex> _lines;
    std::vector<ColorVertex> _tris;
    std::vector<TexVertex>   _texTris;
    GLuint _cvbo = 0;
    GLuint _tvbo = 0;
};
