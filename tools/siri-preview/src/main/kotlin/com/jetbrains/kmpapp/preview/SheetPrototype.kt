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
uniform float uPass;      // 0 = body fill, 1 = rim
uniform float uSpinT;     // integrated spin time (s): speed scale applied on CPU
uniform float uFlip;      // drift/flip amplitude scale
uniform float uScale;     // sheet scale
uniform float uSideK;     // per-state core factor on the side-on boost
uniform float uProc;      // processing shape blend 0..1: sheets -> small grey drops
uniform float uMove;      // processing radial move 0..1 (staged after the shape morph)
uniform float uRing;      // ring radius (shell radii)
uniform float uGather;    // result: drops gathered into a bright white cluster
uniform float uOrbit;     // shared orbit angle (rad)
uniform float uTangle;    // appear: 1 = twisted/compressed pose, 0 = unwound
uniform float uBlend;     // body compositing: 0 = OVER (depth sorted), 1 = Screen
uniform float uLayer;     // 0 = all, 1 = front half only, 2 = behind-centre half only (depth blur)

const float PI = 3.14159265;

float3x3 rotX(float a){ float c=cos(a), s=sin(a); return float3x3(1,0,0, 0,c,-s, 0,s,c); }
float3x3 rotY(float a){ float c=cos(a), s=sin(a); return float3x3(c,0,s, 0,1,0, -s,0,c); }
float3x3 rotZ(float a){ float c=cos(a), s=sin(a); return float3x3(c,-s,0, s,c,0, 0,0,1); }

// width profile along u in [0,1]: rounded root, widest ~0.35, needle tip
// centred sheet: widest at the middle, needles at both ends, slightly asymmetric
float profile(float u){
    float m = sin(clamp(u,0.0,1.0)*PI);
    float oval = sqrt(max(1.0 - (2.0*u-1.0)*(2.0*u-1.0), 0.0));
    // drop: round head, thin tail: sin(pi t)^0.6 * (1 - 0.6 t)
    float drop = pow(m, 0.6) * (1.0 - 0.6*u) * 1.3;
    return mix(pow(m, 0.7), drop, uProc);   // ribbons: full lens-shaped middle, pointed tips
}

// signed-ish distance to sheet k in its local frame; also returns (u, v) in uv
float sheetSdf(float3 p, float twist, float bend, float ws, out float2 uv){
    float L = 1.05;              // half length (shell radii): 85% of diameter x1.5
    float u = (p.x + L) / (2.0*L);
    // bend: centreline arcs in y, centred
    float xs = p.x / L;
    float yc = bend * (xs*xs - 0.33);
    float3 q = float3(p.x, p.y - yc, p.z);
    // twist around the long axis
    float a = twist * (u - 0.5);
    float c = cos(a), s = sin(a);
    float2 yz = float2(c*q.y + s*q.z, -s*q.y + c*q.z);
    float w = profile(clamp(u,0.0,1.0)) * 0.55 * ws;   // half width (shell radii)
    uv = float2(u, yz.x / max(w, 1e-4));
    float vv = clamp(yz.x / max(w, 1e-4), -1.0, 1.0);
    float dz = abs(yz.y - 0.18 * w * vv * vv) - 0.004;
    float dy = abs(yz.x) - w;
    float dx = max(-u, u - 1.0) * 2.0 * L;
    return max(max(dz, dy), dx);
}

half4 sheetColor(int k){
    // 4 main colours + 2 dimmer duplicates
    if (k == 0) return half4(0.18, 0.72, 0.525, 1);   // green #2EB886
    if (k == 1) return half4(0.118, 0.42, 1.0, 1);    // blue #1E6BFF
    if (k == 2) return half4(0.722, 0.133, 0.180, 1); // red #B8222E
    if (k == 3) return half4(0.235, 0.784, 0.94, 1);  // cyan #3CC8F0
    if (k == 4) return half4(0.50, 0.10, 0.13, 1);    // darker red
    return half4(0.05, 0.32, 0.20, 1);                // dark green lens
}

half4 main(float2 fc){
    float2 pS = (fc - 0.5*uSize) / (0.45*uSize.x);
    float2 p = pS / (uScale * mix(0.8, 0.9, uProc));   // sheets kept within ~0.85R   // sheets at 0.9 stay inside the glass   // ribbons fill the glass
    p.y = -p.y;
    float3 ro = float3(0, 0, 5.85);                  // camera 2700/461
    float3 rd = normalize(float3(p * (1.0/5.85) * 5.85 / 5.85, -1.0));
    rd = normalize(float3(p / 5.85, -1.0));
    half3 acc = half3(0); half accA = 0.0;
    float r = length(pS);
    float radial = 1.0 - smoothstep(mix(0.78, 0.80, uProc), mix(0.88, 0.97, uProc), r);
    float q1 = 1.0 - uProc;
    float starBoost = 1.0 + 3.0 * q1 * uSideK * exp(-(r/0.2)*(r/0.2));
    float dd6[6]; half4 cc6[6];
    for (int j = 0; j < 6; j++) { dd6[j] = -1.0; cc6[j] = half4(0); }
    for (int k = 0; k < 6; k++) {
        if (float(k) >= uCount) break;
        float fk = float(k);
        float t = uTime;
        // AE: time*45+value / -time*30+value deg/s, counter-rotating groups; wiggle(0.2,10/15)
        float spin = (mod(fk,2.0) < 0.5 ? 45.0 : -30.0) * PI/180.0;
        float wig = (sin(t*1.257 + fk*1.9) * 0.6 + sin(t*0.71 + fk*3.1) * 0.4) * uFlip;
        float ws = k == 1 ? 1.3 : (k == 2 ? 1.05 : (k == 5 ? 0.8 : 1.0));   // blue = largest central lens
        if (k >= 3) ws *= 0.85;
        if (k == 0) ws *= 0.7;                              // green: thin sliver (edge-on cyan back to 1.0x)
        // per-sheet X/Y/Z rates (deg/s), AE: time*30 / time*45 / -time*30 / -time*45
        float3 rate = k == 0 ? float3(30.0, -45.0, 30.0) : (k == 1 ? float3(-45.0, 30.0, -30.0) : (k == 2 ? float3(45.0, 30.0, -45.0)
                    : (k == 3 ? float3(-30.0, -45.0, 45.0) : (k == 4 ? float3(45.0, -30.0, 30.0) : float3(-45.0, 45.0, -30.0)))));
        float3 off = k == 0 ? float3(0.3, 0.2, 0.0) : (k == 1 ? float3(1.4, 2.1, 1.1) : (k == 2 ? float3(2.6, 0.9, 2.2)
                    : (k == 3 ? float3(0.9, 3.3, 4.0) : (k == 4 ? float3(3.7, 1.6, 0.5) : float3(5.1, 4.4, 2.9)))));
        float3 ang = off + rate * (PI/180.0) * uSpinT * 0.5 + float3(wig*0.35, wig*0.25, -wig*0.2);
        // long-term near edge-on sheets (cyan, dark red): plane normal kept ~perpendicular to the view
        if (k == 3 || k == 4) ang.x = 1.5708 + 0.22 * sin(t * 0.6 + fk) + (k == 4 ? 3.1416 : 0.0);
        // blue: limit flip so its normal stays within ~50 deg of the view (|x|,|y| tilt <= 0.6 rad)
        if (k == 1) { ang.x = 0.6 * sin(ang.x); ang.y = 0.6 * sin(ang.y); }
        float tg = uTangle * (k == 1 ? -1.0 : 1.0);
        ang += float3(2.4, -1.9, 3.1) * tg;
        // processing: all sheets share one in-plane orientation, face the camera, orbit together
        // processing: flat ovals, tangential, in one ring; group spins clockwise (uOrbit decreasing)
        float slot = uOrbit + fk * 1.0471976;
        float3 tgt = float3(0.0, 0.0, slot + 1.5708);
        float3 dlt = ang - tgt;
        dlt = dlt - 6.2831853 * floor((dlt + 3.14159265) / 6.2831853);   // shortest way round
        tgt.z += (fk - 2.5) * 0.14;   // vary orientation slightly per drop
        dlt = ang - tgt; dlt = dlt - 6.2831853 * floor((dlt + 3.14159265) / 6.2831853);
        ang = tgt + dlt * (1.0 - uProc);
        float dsz = k == 0 ? 0.7 : (k == 1 ? 0.85 : (k == 2 ? 1.0 : (k == 3 ? 1.0 : (k == 4 ? 1.15 : 1.3))));
        float sc = mix(1.0, 0.133 * dsz * (1.0 + 0.3 * uGather), uProc);   // x1.4 longer   // staggered drop sizes
        float3 offW = max(uRing * uMove, (0.08 + 0.06 * uGather) * uProc) * float3(cos(slot), sin(slot), 0.0);   // keep ~0.08R apart when gathered
        float rimMul = k >= 4 ? 0.0 : 0.5;
        float3x3 R = rotZ(ang.z) * rotY(ang.y) * rotX(ang.x);
        float3x3 Ri = float3x3(R[0][0],R[1][0],R[2][0], R[0][1],R[1][1],R[2][1], R[0][2],R[1][2],R[2][2]);
        // idle breathing: wiggle(0.2, 10/15) on twist and bend
        float br = sin(6.2832*0.2*t + fk*2.1), br2 = sin(6.2832*0.2*t + fk*1.3 + 1.0);
        float twist = (1.4 + wig * 0.5 + 0.26*br + 5.0*uTangle) * q1;   // wiggle(0.2, ~15 deg)
        float bend = mix(0.49 * (mod(fk,2.0)<0.5 ? 1.0 : -1.0) + wig*0.11 + 0.28*br2 + 0.9*uTangle, 0.05 + 0.05 * fk, uProc);   // staggered 5..30% bend: petal/crescent
        ws = mix(ws, 1.1, uProc);   // x1.6 wider, width:length ~1:2
        if (k == 1) offW += float3(0.0, 0.0, 0.22 * q1);   // blue sits ~0.1R toward the camera, mostly in front of red
        float3 nW = R * float3(0, 0, 1);
        float sideS = 1.0 - abs(nW.z);                    // overall sheet normal vs view
        float pxW = 1.0 / (0.45 * uSize.x * uScale * mix(0.8, 0.9, uProc) * sc) * 1.5;   // AA transition ~1.5 screen px
        float3 o = Ri * ((ro - offW) / sc); float3 d = Ri * rd;
        float tt = length(o) - 1.5; float2 uv = float2(0); bool hit = false; float minD = 1e3; float2 nearUv = float2(0); float nearT = 0.0; float aaK = 1.0;
        for (int i = 0; i < 128; i++) {
            float3 pos = o + d*tt;
            float dd = sheetSdf(pos, twist, bend, ws, uv);
            if (dd < minD) { minD = dd; nearUv = uv; nearT = tt; }
            if (dd < 0.0005) { hit = true; break; }
            tt += max(dd * 0.4, 0.0012);
            if (tt > length(o) + 1.5) break;
        }
        half4 col = half4(pow(sheetColor(k).rgb, half3(2.2)), 1);
        col.rgb = mix(col.rgb, half3(0.62, 0.62, 0.64), half(uProc));   // desaturate to grey-white
        // analytic AA: near-miss within ~1 px still contributes with fractional coverage
        if (!hit && minD < pxW && uPass < 0.5 && uProc < 0.01) { hit = true; tt = nearT; aaK = 1.0 - minD / pxW; }
        if (hit) {
            float3 pos = o + d*tt; float2 tmp;
            float e = 0.0012;
            float3 n = normalize(float3(
                sheetSdf(pos+float3(e,0,0),twist,bend,ws,tmp)-sheetSdf(pos-float3(e,0,0),twist,bend,ws,tmp),
                sheetSdf(pos+float3(0,e,0),twist,bend,ws,tmp)-sheetSdf(pos-float3(0,e,0),twist,bend,ws,tmp),
                sheetSdf(pos+float3(0,0,e),twist,bend,ws,tmp)-sheetSdf(pos-float3(0,0,e),twist,bend,ws,tmp)));
            float ndv = abs(dot(n, -d));
            float fres = pow(1.0 - ndv, 4.0);
            // feathered ring texture: transparent middle, colour toward one edge (AE inverted circle mask)
            float side = clamp(uv.y*0.5+0.5, 0.0, 1.0);
            float ring = 0.35 + 0.65*pow(side, 1.5);
            float rim = smoothstep(0.86, 1.0, abs(uv.y)) * step(0.0, uv.y);
            // depth fog: far half fades to black (AE fog start 2700, range 1000)
            float z = (R * pos).z;
            float fog = pow(clamp(0.4 + 0.6*z/0.8, 0.0, 1.0), 1.6);   // stronger depth fade
            float front = smoothstep(-0.08, 0.12, z);                    // behind the sphere centre -> 0
            fog = mix(fog * mix(0.3 / 0.4, 1.0, front), 1.0, uProc);    // back halves ~30%
            float rimFront = mix(front, 1.0, uProc);
            float centre = 1.0 - smoothstep(0.15, 1.0, abs(2.0*uv.x - 1.0));
            float body = (0.18 + 0.82*centre) * (1.0 - fres) * (0.5 + 0.5*ring);
            float thinRim = pow(1.0 - ndv, 9.0);
            if (uPass < 0.5) {
                // v: 0 at the rim (bright, hard) side .. 1 at the far edge (dark, feathered 15%)
                float v = 1.0 - side;
                float bri = mix(0.95, 0.3, v) * (1.0 - 0.75 * pow(clamp((1.0 - uSideK) / 0.25, 0.0, 1.0), 0.5) * exp(-(r/0.35)*(r/0.35))) * (1.0 + 0.8 * (0.5 * uSideK * exp(-(r/0.13)*(r/0.13)) + 0.0)) * mix(1.0 + 0.5 * fres, 1.0, uBlend);   // gloss 0 in Screen mode
                float a = 0.9 * (1.0 - smoothstep(0.97, 1.0, abs(uv.y))) * (0.75 + 0.25*centre) * fog;   // solid body, ~3% feather
                // solid hard-edged oval with a slight soft edge
                // feathered white disc -> soft oval when flat
                float eo = 1.0 - smoothstep(0.93, 1.0, abs(uv.y));   // nearly hard edge
                bri = mix(bri, mix(mix(0.5, 0.75, uMove), 0.8, uGather), uProc);   // 0.5 while shrinking in place / gathered, below the glow threshold
                a = mix(a, 0.9 * eo, uProc);
                // depth layer split: world z of the hit (camera at +z)
                float zw = (R * (pos * sc)).z + offW.z;
                float wf = smoothstep(-0.08, 0.08, zw);
                if (uLayer > 0.5) a *= (uLayer < 1.5 ? wf : 1.0 - wf);
                bri *= 1.0 + 2.5 * q1 * pow(sideS, 4.0) * exp(-(r/0.45)*(r/0.45));   // whole-sheet brightening when edge-on
                a *= aaK;
                if (k == 2) { bri *= mix(0.8, 1.0, uProc); a *= mix(0.7 / 0.9, 1.0, uProc); }   // red: darker, alpha 0.7
                bri *= 1.0 + 0.8 * q1 * exp(-(r/0.5)*(r/0.5));                                   // internal brightness gradient
                bri *= mix(1.0, mix(1.0, 0.7, smoothstep(0.7, 1.0, abs(uv.y))), q1);           // darker toward sheet boundaries
                // edge-on blades go white; pre-cancel the final x1.3 saturation by the same amount
                float wm = 0.6 * q1 * pow(sideS, 4.0) * exp(-(r/0.28)*(r/0.28));
                col.rgb = mix(col.rgb, half3(1.0), half(wm));
                half lc = dot(col.rgb, half3(0.2126, 0.7152, 0.0722));
                col.rgb = max(mix(half3(lc), col.rgb, half(mix(1.0, 1.0/1.3, wm))), half3(0));
                half4 c4 = half4(col.rgb * half(bri), half(a));
                if (uBlend > 0.5) {
                    half3 e = min(c4.rgb * c4.a, half3(1.0));
                    acc = acc + e - acc * e;          // Screen
                } else {
                    for (int j = 0; j < 6; j++) { if (j == k) { dd6[j] = tt; cc6[j] = c4; } }
                }
            } else {
                // first hit only; far side attenuated by fog^2; tips fade radially
                float vE = 1.0 - side;
                float edgeLine = 1.0 - smoothstep(0.0, 0.08, vE);
                float rimK = edgeLine * (0.35 + 0.65 * pow(1.0 - ndv, 2.0)) * starBoost;
                half3 bright = mix(min(col.rgb * 1.35 + half3(0.12), half3(1.0)), half3(1.0), half(clamp((starBoost - 1.0) / 3.0, 0.0, 1.0)));
                acc += bright * half(rimFront * rimMul * q1 * 0.7 * clamp(rimK * 2.2, 0.0, 5.0) * fog * fog * (1.0 - smoothstep(0.35, 0.75, r)));
            }
        } else if (minD < 0.012) {
            // edge-on: sheet thinner than a pixel -> bright needle line
            float g = 1.0 - minD/0.012;
            if (uPass > 0.5) acc += min(col.rgb * 1.35 + half3(0.12), half3(1.0)) * half(g*g*0.7*starBoost*q1*rimMul);
        }
    }
    if (uPass < 0.5 && uBlend < 0.5) {
        // back-to-front OVER (largest ray distance first)
        for (int s = 0; s < 6; s++) {
            float dm = -1.0; float bi = -1.0;
            for (int j = 0; j < 6; j++) { if (dd6[j] > dm) { dm = dd6[j]; bi = float(j); } }
            if (dm < 0.0) break;
            half4 c4 = half4(0);
            for (int j = 0; j < 6; j++) { if (float(j) == bi) { c4 = cc6[j]; dd6[j] = -1.0; } }
            acc = c4.rgb * c4.a + acc * (1.0 - c4.a); accA = c4.a + accA * (1.0 - c4.a);
        }
    }
    acc *= half(radial);
    if (uLayer > 0.5 && uBlend < 0.5) return half4(acc, accA * half(radial));   // premultiplied, for layer OVER
    return half4(acc, 1);
}
"""

internal data class OrbParams(
    val spinT: Float, val flip: Float = 1f, val scale: Float = 1f, val core: Float = 1f, val fade: Float = 1f,
    val sideK: Float = 1f, val shell: Float = 1f,
    val proc: Float = 0f, val orbit: Float = 0f, val tangle: Float = 0f,
    val move: Float = 0f, val ring: Float = 0.4f, val gather: Float = 0f, val flash: Float = 0f,
)

internal fun renderSheetStills(
    outDir: Path, count: Int, fps: Double, seconds: Double, size: Int = 870,
    prefix: String = "s",
    paramsAt: (Int, Double) -> OrbParams = { _, t -> OrbParams(spinT = t.toFloat(), sideK = (System.getenv("SIDEK") ?: "1.25").toFloat(), core = (System.getenv("CORE") ?: System.getenv("SIDEK") ?: "1.25").toFloat()) },
) {
    val effect = RuntimeEffect.makeForShader(SheetSksl)
    val threshold = RuntimeEffect.makeForShader("""
        uniform shader img;
        half4 main(float2 c) {
            half3 x = max(img.eval(c).rgb, half3(0));
            half l = dot(x, half3(0.2126, 0.7152, 0.0722));
            return half4(x * (max(l - 1.0, 0.0) / max(l, 1e-4)), 1);
        }
    """)
    val tonemap = RuntimeEffect.makeForShader("""
        uniform shader img;
        uniform float2 uSize;
        uniform float uFade;
        half4 main(float2 c) {
            float rr = length(c - 0.5*uSize) / (0.45*uSize.x);
            half3 x = max(img.eval(c).rgb, half3(0)) * 0.765;   // exposure -15%
            x *= half((1.0 - smoothstep(1.0, 1.08, rr)) * uFade);
            x = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
            half l = dot(x, half3(0.2126, 0.7152, 0.0722));
            x = clamp(mix(half3(l), x, 1.3), 0.0, 1.0);
            return half4(pow(x, half3(1.0 / 2.2)), 1);
        }
    """)
    val spillFx = RuntimeEffect.makeForShader("""
        uniform shader img; uniform float2 uSize; uniform float uK;
        half4 main(float2 c) {
            float r = length(c - 0.5*uSize) / (0.45*uSize.x);
            half3 x = max(img.eval(c).rgb, half3(0));
            half l = min(dot(x, half3(0.2126, 0.7152, 0.0722)) * 3.0, 1.0);
            half w = half(uK * (1.0 - smoothstep(0.0, 0.3, r)));
            return half4(half3(l * w), 1);
        }
    """)
    Files.createDirectories(outDir)
    val n = (fps * seconds).toInt().coerceAtLeast(1)
    for (i in 0 until n) {
        val t = i / fps
        val prm = paramsAt(i, t)
        if (i < (System.getenv("SKIP") ?: "0").toInt()) continue   // resume a partial render
        if (i > (System.getenv("END") ?: "999999").toInt()) break
        fun pass(id: Float, layer: Float = 0f): org.jetbrains.skia.Image {
            val b = RuntimeShaderBuilder(effect)
            b.uniform("uSize", size.toFloat(), size.toFloat())
            b.uniform("uTime", t.toFloat())
            b.uniform("uCount", count.toFloat())
            b.uniform("uPass", id)
            b.uniform("uSpinT", prm.spinT)
            b.uniform("uFlip", prm.flip)
            b.uniform("uScale", prm.scale)
            b.uniform("uSideK", prm.sideK)
            b.uniform("uProc", prm.proc)
            b.uniform("uMove", prm.move)
            b.uniform("uRing", prm.ring)
            b.uniform("uGather", prm.gather)
            b.uniform("uOrbit", prm.orbit)
            b.uniform("uTangle", prm.tangle)
            b.uniform("uBlend", (System.getenv("BLEND") ?: "0").toFloat())
            b.uniform("uLayer", layer)
            val surf = Surface.makeRaster(org.jetbrains.skia.ImageInfo(size, size, org.jetbrains.skia.ColorType.RGBA_F16, org.jetbrains.skia.ColorAlphaType.PREMUL))
            surf.canvas.clear(if (layer > 0f) Color.TRANSPARENT else Color.BLACK)
            surf.canvas.drawPaint(Paint().apply { this.shader = b.makeShader() })
            return surf.makeImageSnapshot()
        }
        val radius = size * 0.45f
        val cx = size / 2f
        fun f16() = Surface.makeRaster(org.jetbrains.skia.ImageInfo(size, size, org.jetbrains.skia.ColorType.RGBA_F16, org.jetbrains.skia.ColorAlphaType.PREMUL))
        // Depth blur: front half sharp, sheets behind the centre blurred ~4% R (idle only; drops stay sharp).
        val bodyImg = if (prm.proc < 0.99f) {
            val front = pass(0f, 1f); val backImg = pass(0f, 2f)
            val bs = f16(); bs.canvas.clear(Color.BLACK)
            val bb = radius * 0.04f * (1f - prm.proc) + 0.01f
            bs.canvas.drawImage(backImg, 0f, 0f, Paint().apply { imageFilter = ImageFilter.makeBlur(bb, bb, FilterTileMode.DECAL); blendMode = BlendMode.PLUS })
            bs.canvas.drawImage(front, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC_OVER })   // front sharp, OVER the blurred back
            bs.makeImageSnapshot()
        } else pass(0f)
        val k = size / 1740f
        // 1) HDR scene: body (OVER in shader, slight soften) + sharp rim + HDR core.
        val scene = f16()
        val c = scene.canvas
        c.clear(Color.BLACK)
        c.drawImage(bodyImg, 0f, 0f, Paint().apply {
            imageFilter = ImageFilter.makeBlur(0.6f, 0.6f, FilterTileMode.DECAL)   // no extra soften on the front
            blendMode = BlendMode.PLUS
        })
        // White spill: pure white (luminance of sheet coverage only, no hue), Screen, falls to 0 by 0.3R.
        run {
            val k0 = ((System.getenv("SPILL") ?: "1").toFloat() * 0.3f * prm.sideK * prm.sideK * (1f - prm.move) * (1f - smoothstepF(0f, 0.03f, prm.proc))).coerceAtLeast(0f)   // off in processing/result until regrown
            if (k0 > 0.001f) {
                val sb = RuntimeShaderBuilder(spillFx)
                sb.child("img", bodyImg.makeShader())
                sb.uniform("uSize", size.toFloat(), size.toFloat()); sb.uniform("uK", k0)
                c.drawPaint(Paint().apply { shader = sb.makeShader(); blendMode = BlendMode.SCREEN })
            }
        }
        // HDR core (peak ~4): additive passes, halo ~15% radius.
        for ((r, sig, reps) in listOf(
            Triple(radius * 0.066f, radius * 0.048f, 1),   // core radius back to 1.0, intensity x1.3 (was 0.7)
            Triple(radius * 0.036f, radius * 0.021f, 2),
            Triple(radius * 0.015f, radius * 0.006f, 4),
        )) {
          val total = 1.3f * 0.8f * reps * prm.core * (1f - kotlin.math.max(prm.move, prm.proc))
          var left = total
          while (left > 0.001f) {
            val a = left.coerceAtMost(1f); left -= a
            c.drawCircle(cx, cx, r * prm.scale.coerceIn(0.55f, 1.5f), Paint().apply {
                color = ((a * 255).toInt() shl 24) or 0xFFFFFF
                blendMode = BlendMode.PLUS
                maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, sig)
            })
          }
        }
        // Wide soft glow ~0.5 R, strength ~0.35, fast falloff.
        run {
            val g = 0.15f * prm.core * (1f - kotlin.math.max(prm.move, prm.proc))
            if (g > 0.001f) c.drawCircle(cx, cx, radius * 0.18f, Paint().apply {
                color = ((g * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
                blendMode = BlendMode.PLUS
                maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.16f)
            })
        }
        // Gather flash: white core ~0.1 R, peak ~1.6
        if (prm.flash > 0.01f) {
            var left = prm.flash * 2.5f
            while (left > 0.001f) {
                val a = left.coerceAtMost(1f); left -= a
                c.drawCircle(cx, cx, radius * 0.058f, Paint().apply {
                    color = ((a * 255).toInt() shl 24) or 0xFFFFFF
                    blendMode = BlendMode.PLUS
                    maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.03f)
                })
            }
        }
        val sceneImg = scene.makeImageSnapshot()
        // 2) Threshold: keep HDR luminance above 1.0, then wide glows 0.15 / 0.3.
        val thb = RuntimeShaderBuilder(threshold)
        thb.child("img", sceneImg.makeShader())
        val hot = f16()
        hot.canvas.drawPaint(Paint().apply { shader = thb.makeShader() })
        val hotImg = hot.makeImageSnapshot()
        val out = f16()
        out.canvas.drawImage(sceneImg, 0f, 0f)
        for ((sigma, alpha) in listOf(200f * k to 0.15f, 80f * k to 0.3f, 30f * k to 0.5f)) {
            out.canvas.drawImage(hotImg, 0f, 0f, Paint().apply {
                imageFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL)
                blendMode = BlendMode.PLUS
                this.alpha = (alpha * 255).toInt()
            })
        }
        // Tonemap: ACES approx in linear, saturation x1.3, then sRGB encode.
        val tb = RuntimeShaderBuilder(tonemap)
        tb.child("img", out.makeImageSnapshot().makeShader())
        tb.uniform("uSize", size.toFloat(), size.toFloat())
        tb.uniform("uFade", prm.fade.coerceIn(0f, 1f))
        val fin = Surface.makeRasterN32Premul(size, size)
        fin.canvas.drawPaint(Paint().apply { shader = tb.makeShader() })
        val fc = fin.canvas
        // Shell glow after tonemap: 30%, ring 0.85..1.0 only.
        // Interior base #2D2C32, feathered edge; outside stays black.
        // Ball base: deep blue #0B1020 under everything; inner rim glow (0.8..1.0 R) from blurred sheet colour.
        val sh = prm.shell.coerceIn(0f, 1f)
        fc.drawCircle(cx, cx, radius * 0.985f, Paint().apply {
            color = ((0xFF * sh).toInt() shl 24) or 0x05070D
            blendMode = BlendMode.PLUS
            maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.01f)
        })
        val rimA = (sh * (1f - prm.move)).coerceIn(0f, 1f)
        if (rimA > 0.01f) {
            fc.saveLayer(null, Paint().apply { blendMode = BlendMode.PLUS; alpha = (55 * rimA).toInt() })
            fc.drawImage(bodyImg, 0f, 0f, Paint().apply {
                imageFilter = ImageFilter.makeBlur(radius * 0.25f, radius * 0.25f, FilterTileMode.DECAL)
                colorFilter = org.jetbrains.skia.ColorFilter.makeMatrix(org.jetbrains.skia.ColorMatrix(
                    0.15f, 0.15f, 0.15f, 0f, 0f, 0.8f, 0.8f, 0.8f, 0f, 0f, 0.7f, 0.7f, 0.7f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            })
            // keep only the inner band 0.8..1.0 R, nothing outside the ball
            fc.drawCircle(cx, cx, radius * 0.9f, Paint().apply {
                blendMode = BlendMode.DST_IN
                mode = org.jetbrains.skia.PaintMode.STROKE
                strokeWidth = radius * 0.17f
                maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.025f)
            })
            fc.drawCircle(cx, cx, radius * 0.995f, Paint().apply { blendMode = BlendMode.DST_IN })
            fc.restore()
        }
        // Processing disc: #1C1C1F at 85%, radius 1.7x the ring; shrinks and fades with the ring (move).
        if (prm.move > 0.01f) {
            fc.drawCircle(cx, cx, 1.7f * prm.ring * 0.9f * radius * prm.move, Paint().apply {
                color = ((0xD9 * prm.move).toInt().coerceIn(0, 255) shl 24) or 0x1C1C1F
                blendMode = BlendMode.PLUS
                maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.012f)
            })
        }
        val data = fin.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!
        Files.write(outDir.resolve("$prefix%03d.png".format(i)), data.bytes)
    }
}

/** Six-state demo in the preview: eased params per state, synthetic voice envelope. */
internal val SheetDemoStates = listOf("Appear" to 1.5, "Idle" to 6.0, "Listening" to 2.5, "Processing" to 3.0, "Result" to 3.0, "Disappear" to 1.5)

internal fun renderSheetDemo(outDir: Path, fps: Double = 10.0) {
    data class Target(val speed: Float, val flip: Float, val scale: Float, val core: Float)
    val tg = mapOf(
        "Appear" to Target(1.0f, 1.0f, 1.0f, 1.0f), "Idle" to Target(1.0f, 1.0f, 1.0f, 1.0f),
        "Listening" to Target(1.3f, 1.3f, 1.0f, 1.0f), "Processing" to Target(1.0f, 1.0f, 1.0f, 1.0f),
        "Result" to Target(0.8f, 0.8f, 1.0f, 1.1f), "Disappear" to Target(1.0f, 1.0f, 0.0f, 0.0f),
    )
    val starts = SheetDemoStates.runningFold(0.0) { a, b -> a + b.second }
    var sideK = 1.25f; var speed = 1f; var flip = 1f; var scale = 0.0f; var core = 0f; var spinT = 0f; var orbit = 0f
    val total = starts.last()
    val dt = (1.0 / fps).toFloat()
    val ease = 1f - kotlin.math.exp(-dt / 0.35f)
    val labels = StringBuilder()
    val back = 1.0f   // Result: pieces pulled back to centre over 1.0 s, then flash + bounce
    renderSheetStills(outDir, 6, fps, total, prefix = "d", paramsAt = { i, t ->
        val si = (starts.indexOfLast { it <= t }).coerceIn(0, SheetDemoStates.lastIndex)
        val name = SheetDemoStates[si].first; val st = tg.getValue(name)
        val tE = (t - starts[si]).toFloat()
        speed += (st.speed - speed) * ease; flip += (st.flip - flip) * ease
        scale += (st.scale - scale) * ease; core += (st.core - core) * ease
        val sideT = when (name) { "Listening" -> 1.0625f; "Processing" -> 0.9375f; "Result" -> 1.375f; else -> 1.25f }
        sideK += (sideT - sideK) * ease
        val voice = if (name == "Listening") {
            val syl = kotlin.math.abs(kotlin.math.sin(t * 12.6)).toFloat()
            val phrase = (0.5 + 0.5 * kotlin.math.sin(t * 2.1)).toFloat()
            (syl * phrase - 0.5f) * 0.4f
        } else 0f
        val burst = (voice - 0.1f).coerceAtLeast(0f) * 30f     // spin bursts on voice peaks
        spinT += (speed + burst) * dt
        orbit -= (2.0 * Math.PI * 0.5).toFloat() * dt   // constant clockwise group spin
        // staging: shape morph in place first, radial move ~0.2 s later
        val proc = when (name) {
            "Processing" -> smoothstepF(0f, 0.7f, tE)
            "Result" -> 1f - smoothstepF(0.4f, 1.0f, tE)       // drops grow back after the gather
            else -> 0f
        }
        val move = when (name) {
            "Processing" -> smoothstepF(0.1f, 0.8f, tE)
            "Result" -> 1f - smoothstepF(0f, 0.4f, tE)          // gather to the centre first
            else -> 0f
        }
        val ring = when (name) {
            "Processing" -> 0.4f + 0.15f * smoothstepF(0.5f, 3.0f, tE)
            "Result" -> 0.55f
            else -> 0.4f
        }
        val gather = if (name == "Result") (smoothstepF(0.15f, 0.4f, tE) * (1f - smoothstepF(0.4f, 0.8f, tE))) else 0f
        labels.append("$i $name\n")
        var dScale = 0f; var dCore = 0f; var fade = 1f; var tangle = 0f
        when (name) {
            "Appear" -> { val e = smoothstepF(0f, 1f, tE); scale = e; tangle = 1f - e; fade = smoothstepF(0f, 0.4f, tE); core = e }
            "Result" -> {
                // reverse morph (1.0 s), then white flash 1.6 -> settle and spring bounce to ~1.08
                // flash at the smallest (gathered) moment, then bounce as the orb regrows
                val r = (tE - 0.4f).coerceAtLeast(0f)
                if (tE >= 0.4f) {
                    val ex = kotlin.math.exp(-4f * r)
                    val sp = 1.0f + 0.04f * (1f - ex * kotlin.math.cos(8f * r)) + 0.1f * ex * kotlin.math.sin(8f * r)   // peak ~1.08, settles 1.04
                    dScale = sp - scale
                }
            }
            "Disappear" -> {
                dCore = (1f - kotlin.math.abs(tE - 0.76f) / 0.06f).coerceIn(0f, 1f) * 1.6f
                fade = if (tE < 0.82f) 1f else 0f
            }
        }
        val flash = if (name == "Result") 1.6f * kotlin.math.exp(-((tE - 0.4f) / 0.09f).let { it * it }) else 0f
        var sideOut = sideK
        if (name == "Processing") { val k0 = 1f - smoothstepF(0f, 0.3f, tE); sideOut = sideK * k0; core *= k0 }
        val regrow = 1f - proc
        val shell = if (name == "Processing") 1f - move
            else if (name == "Result") smoothstepF(0.6f, 1.0f, regrow)
            else if (name == "Disappear") fade * (1f - smoothstepF(0f, 0.6f, tE)) else fade
        OrbParams(spinT, flip, (scale + dScale) * (1f + voice), (core + dCore) * (1f + voice), fade, sideOut, shell, proc, orbit, tangle, move, ring, gather, flash)
    })
    Files.writeString(outDir.resolve("labels.txt"), labels.toString())
}

private fun smoothstepF(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t)
}
