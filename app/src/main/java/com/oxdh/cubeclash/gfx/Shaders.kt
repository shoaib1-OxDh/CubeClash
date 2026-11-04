package com.oxdh.cubeclash.gfx

/** GLSL ES 3.00 sources. World space: X right, Y up, engine +Y (screen "north") = GL -Z. */
object Shaders {
    private const val FOG = """
        uniform vec3 uFogColor;
        uniform vec2 uFog; // start, end
        float fogFactor(float d) { return clamp((d - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0); }
    """

    const val CUBE_VS = """#version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec2 aUv;
        layout(location = 3) in vec4 iA; // x, y, z, size
        layout(location = 4) in vec4 iB; // dirX, dirY (engine plane), atlas cell, height multiplier
        layout(location = 5) in vec4 iC; // tint rgb, emission
        uniform mat4 uVP;
        uniform vec2 uCells;
        out vec2 vUv;
        out vec3 vN;
        out vec3 vWorld;
        out vec4 vTint;
        void main() {
            vec3 F = vec3(iB.x, 0.0, -iB.y);   // cube forward (local -Z)
            vec3 R = vec3(iB.y, 0.0, iB.x);    // cube right (local +X)
            vec3 p = aPos * vec3(iA.w, iA.w * iB.w, iA.w);
            vec3 w = R * p.x + vec3(0.0, p.y, 0.0) - F * p.z + iA.xyz;
            vN = R * aNormal.x + vec3(0.0, aNormal.y, 0.0) - F * aNormal.z;
            float col = mod(iB.z, uCells.x);
            float row = floor(iB.z / uCells.x);
            vUv = (vec2(col, row) + 0.012 + aUv * 0.976) / uCells;
            vWorld = w;
            vTint = iC;
            gl_Position = uVP * vec4(w, 1.0);
        }
    """

    val CUBE_FS = """#version 300 es
        precision mediump float;
        in vec2 vUv;
        in vec3 vN;
        in vec3 vWorld;
        in vec4 vTint;
        uniform sampler2D uTex;
        uniform vec3 uCam;
        uniform vec3 uLight;
        $FOG
        out vec4 outColor;
        void main() {
            vec3 n = normalize(vN);
            vec3 base = texture(uTex, vUv).rgb * vTint.rgb;
            float ndl = max(dot(n, uLight), 0.0);
            float wrap = clamp((dot(n, uLight) + 0.4) / 1.4, 0.0, 1.0);
            vec3 light = vec3(0.50, 0.53, 0.62) + vec3(0.82, 0.80, 0.74) * (ndl * 0.6 + wrap * 0.4);
            vec3 v = normalize(uCam - vWorld);
            float rim = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 3.0) * 0.22;
            vec3 c = base * light + rim + base * vTint.a;
            c = mix(c, uFogColor, fogFactor(distance(uCam, vWorld)));
            outColor = vec4(c, 1.0);
        }
    """

    const val GROUND_VS = """#version 300 es
        layout(location = 0) in vec2 aXZ;
        uniform mat4 uVP;
        out vec3 vWorld;
        void main() {
            vWorld = vec3(aXZ.x, 0.0, aXZ.y);
            gl_Position = uVP * vec4(vWorld, 1.0);
        }
    """

    val GROUND_FS = """#version 300 es
        precision highp float;
        in vec3 vWorld;
        uniform vec3 uCam;
        uniform float uRadius;
        uniform vec3 uGround;
        uniform vec3 uGrid;
        uniform vec3 uOutside;
        uniform vec3 uBoundary;
        $FOG
        out vec4 outColor;
        float lines(vec2 p, float width) {
            vec2 fw = max(fwidth(p), vec2(1e-4));
            vec2 d = abs(fract(p - 0.5) - 0.5);
            vec2 l = 1.0 - smoothstep(vec2(width) - fw, vec2(width) + fw, d);
            return max(l.x, l.y);
        }
        void main() {
            vec2 p = vWorld.xz / 4.0;
            float minor = lines(p, 0.03);
            float major = lines(p * 0.25, 0.015);
            float r = length(vWorld.xz);
            vec3 col = r < uRadius ? uGround : uOutside;
            col = mix(col, uGrid, clamp(minor * 0.55 + major * 0.35, 0.0, 1.0));
            col *= mix(1.0, 0.88, clamp(r / uRadius, 0.0, 1.0));
            float band = 1.0 - clamp(abs(r - uRadius) / 1.2, 0.0, 1.0);
            float warn = clamp(1.0 - (uRadius - r) / 12.0, 0.0, 1.0) * (r < uRadius ? 1.0 : 0.0);
            col = mix(col, uBoundary, clamp(band + warn * 0.25, 0.0, 1.0));
            col = mix(col, uFogColor, fogFactor(distance(uCam, vWorld)));
            outColor = vec4(col, 1.0);
        }
    """

    const val SPRITE_VS = """#version 300 es
        layout(location = 0) in vec2 aCorner;
        layout(location = 1) in vec4 iP; // x, y, z, radius
        layout(location = 2) in vec4 iCol;
        layout(location = 3) in float iType;
        uniform mat4 uVP;
        uniform int uBillboard;
        uniform vec3 uCamRight;
        uniform vec3 uCamUp;
        out vec2 vCorner;
        out vec4 vCol;
        out float vType;
        out vec3 vWorld;
        void main() {
            vec3 w = uBillboard == 1
                ? iP.xyz + (uCamRight * aCorner.x + uCamUp * aCorner.y) * iP.w
                : iP.xyz + vec3(aCorner.x, 0.0, aCorner.y) * iP.w;
            vCorner = aCorner;
            vCol = iCol;
            vType = iType;
            vWorld = w;
            gl_Position = uVP * vec4(w, 1.0);
        }
    """

    val SPRITE_FS = """#version 300 es
        precision mediump float;
        in vec2 vCorner;
        in vec4 vCol;
        in float vType;
        in vec3 vWorld;
        uniform vec3 uCam;
        $FOG
        out vec4 outColor;
        void main() {
            float d = length(vCorner);
            if (d > 1.0) discard;
            float a;
            if (vType < 0.5) { float k = 1.0 - d; a = k * k * (3.0 - 2.0 * k); }        // soft disc
            else if (vType < 1.5) { a = smoothstep(0.72, 0.8, d) * (1.0 - smoothstep(0.92, 1.0, d)); } // ring
            else { a = 1.0 - smoothstep(0.55, 1.0, d); }                                 // particle
            float fog = fogFactor(distance(uCam, vWorld));
            outColor = vec4(vCol.rgb, vCol.a * a * (1.0 - fog));
        }
    """
}
