package com.jetbrains.kmpapp.preview

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path

/**
 * Prototype: one Element3D-style ribbon as a raymarched SkSL sheet.
 * Sheet = thin slab, length along x, width along y, twisted about x and bent,
 * width profile with a rounded root and a needle tip. Fresnel gives a sharp
 * bright rim at grazing angles, so an edge-on sheet collapses to a bright needle.
 */
internal val SheetSksl = """
uniform float2 uSize;
uniform float uTime;      // seconds
uniform float uCount;     // number of sheets (1..4)

const float PI = 3.14159265;

float3x3 rotX(float a){ float c=cos(a), s=sin(a); return float3x3(1,0,0, 0,c,-s, 0,s,c); }
float3x3 rotY(float a){ float c=cos(a), s=sin(a); return float3x3(c,0,s, 0,1,0, -s,0,c); }
float3x3 rotZ(float a){ float c=cos(a), s=sin(a); return float3x3(c,-s,0, s,c,0, 0,0,1); }

// width profile along u in [0,1]: rounded root, widest ~0.35, needle tip
float profile(float u){
    if (u < 0.0 || u > 1.0) return -1.0;
    float rise = sin(clamp(u/0.35,0.0,1.0)*PI*0.5);
    float fall = 1.0 - smoothstep(0.35, 1.0, u);
    return rise * pow(fall, 0.9);
}

// signed-ish distance to sheet k in its local frame; also returns (u, v) in uv
float sheetSdf(float3 p, float twist, float bend, out float2 uv){
    float L = 1.05;              // length in shell radii
    float u = (p.x + 0.12) / L;  // root slightly behind centre
    // bend: centreline curves in y
    float yc = bend * u * u;
    float3 q = float3(p.x, p.y - yc, p.z);
    // twist around the long axis
    float a = twist * (u - 0.3);
    float c = cos(a), s = sin(a);
    float2 yz = float2(c*q.y + s*q.z, -s*q.y + c*q.z);
    float w = profile(clamp(u,0.0,1.0)) * 0.24;   // half width (shell radii)
    uv = float2(u, yz.x / max(w, 1e-4));
    float dz = abs(yz.y) - 0.004;
    float dy = abs(yz.x) - w;
    float dx = max(-u, u - 1.0) * L;
    return max(max(dz, dy), dx);
}

half4 sheetColor(int k){
    if (k == 0) return half4(1.0, 0.176, 0.333, 1);   // #FF2D55
    if (k == 1) return half4(0.039, 0.518, 1.0, 1);   // #0A84FF
    if (k == 2) return half4(0.0, 1.0, 0.8, 1);       // #00FFCC
    return half4(0.0, 0.19, 1.0, 1);                  // #0030FF
}

half4 main(float2 fc){
    float2 p = (fc - 0.5*uSize) / (0.66*uSize.x);   // ribbons fill the glass
    p.y = -p.y;
    float3 ro = float3(0, 0, 5.85);                  // camera 2700/461
    float3 rd = normalize(float3(p * (1.0/5.85) * 5.85 / 5.85, -1.0));
    rd = normalize(float3(p / 5.85, -1.0));
    half3 acc = half3(0);
    for (int k = 0; k < 4; k++) {
        if (float(k) >= uCount) break;
        float fk = float(k);
        float t = uTime;
        // AE: time*45+value / -time*30+value deg/s, counter-rotating groups; wiggle(0.2,10/15)
        float spin = (mod(fk,2.0) < 0.5 ? 45.0 : -30.0) * PI/180.0;
        float wig = sin(t*1.257 + fk*1.9) * 0.6 + sin(t*0.71 + fk*3.1) * 0.4;
        float3x3 R = rotZ(fk*1.7 + t*spin) * rotX(0.9 + fk*1.3 + t*spin*0.7 + wig*0.5) * rotY(0.3*fk + wig*0.25);
        float3x3 Ri = float3x3(R[0][0],R[1][0],R[2][0], R[0][1],R[1][1],R[2][1], R[0][2],R[1][2],R[2][2]);
        float twist = 2.2 + wig * 0.6;
        float bend = 0.35 * (mod(fk,2.0)<0.5 ? 1.0 : -1.0) + wig*0.08;
        float3 o = Ri * ro; float3 d = Ri * rd;
        float tt = 4.2; float2 uv = float2(0); bool hit = false; float minD = 1e3; float2 nearUv = float2(0);
        for (int i = 0; i < 72; i++) {
            float3 pos = o + d*tt;
            float dd = sheetSdf(pos, twist, bend, uv);
            if (dd < minD) { minD = dd; nearUv = uv; }
            if (dd < 0.0008) { hit = true; break; }
            tt += max(dd * 0.55, 0.002);
            if (tt > 7.6) break;
        }
        half4 col = sheetColor(k);
        if (hit) {
            float3 pos = o + d*tt; float2 tmp;
            float e = 0.002;
            float3 n = normalize(float3(
                sheetSdf(pos+float3(e,0,0),twist,bend,tmp)-sheetSdf(pos-float3(e,0,0),twist,bend,tmp),
                sheetSdf(pos+float3(0,e,0),twist,bend,tmp)-sheetSdf(pos-float3(0,e,0),twist,bend,tmp),
                sheetSdf(pos+float3(0,0,e),twist,bend,tmp)-sheetSdf(pos-float3(0,0,e),twist,bend,tmp)));
            float ndv = abs(dot(n, -d));
            float fres = pow(1.0 - ndv, 4.0);
            // feathered ring texture: transparent middle, colour toward one edge (AE inverted circle mask)
            float side = clamp(uv.y*0.5+0.5, 0.0, 1.0);
            float ring = 0.35 + 0.65*pow(side, 1.5);
            float rim = smoothstep(0.86, 1.0, abs(uv.y)) * step(0.0, uv.y);
            // depth fog: far half fades to black (AE fog start 2700, range 1000)
            float z = (R * pos).z;
            float fog = clamp(0.55 + 0.45*z/0.9, 0.0, 1.0);
            float a = clamp(ring*0.75 + rim*0.6 + fres*1.2, 0.0, 1.6) * fog;
            acc += col.rgb * half(a) + half3(fres*0.35*fog + rim*0.12*fog);
        } else if (minD < 0.012) {
            // edge-on: sheet thinner than a pixel -> bright needle line
            float g = 1.0 - minD/0.012;
            acc += (col.rgb*0.9 + half3(0.25)) * half(g*g*0.8);
        }
    }
    return half4(acc, 1);
}
"""

internal fun renderSheetStills(outDir: Path, count: Int, fps: Double, seconds: Double, size: Int = 870) {
    val effect = RuntimeEffect.makeForShader(SheetSksl)
    Files.createDirectories(outDir)
    val n = (fps * seconds).toInt().coerceAtLeast(1)
    for (i in 0 until n) {
        val t = i / fps
        val b = RuntimeShaderBuilder(effect)
        b.uniform("uSize", size.toFloat(), size.toFloat())
        b.uniform("uTime", t.toFloat())
        b.uniform("uCount", count.toFloat())
        val shader = b.makeShader()
        val base = Surface.makeRasterN32Premul(size, size)
        base.canvas.clear(Color.BLACK)
        base.canvas.drawPaint(Paint().apply { this.shader = shader })
        val sheetImg = base.makeImageSnapshot()
        val out = Surface.makeRasterN32Premul(size, size)
        val c = out.canvas
        c.clear(Color.BLACK)
        // layered glow: wide + medium blur, then sharp sheet, all additive
        for ((sigma, alpha) in listOf(size * 0.06f to 0.55f, size * 0.018f to 0.7f)) {
            c.drawImage(sheetImg, 0f, 0f, Paint().apply {
                imageFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL)
                blendMode = BlendMode.PLUS
                this.alpha = (alpha * 255).toInt()
            })
        }
        c.drawImage(sheetImg, 0f, 0f, Paint().apply { blendMode = BlendMode.PLUS })
        val data = out.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!
        Files.write(outDir.resolve("s%02d.png".format(i)), data.bytes)
    }
}
