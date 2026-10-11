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
uniform float uGrey;      // grey/desaturation amount of the drops (decoupled from shape in Result)
uniform float uListen;
uniform float uBodyA;
uniform float uRes;       // result: white only on sheet edges     // sheet opacity multiplier (appear)    // listening: rounder petals, brighter, slightly less blue
uniform float uLayer;     // 0 = all, 1 = front half only, 2 = behind-centre half only (depth blur)
uniform float uIdle;
uniform float uDebug;     // 1 = colour each sheet by family (R/G/B) for identity-based area stats      // 1 = idle look (silk, equal RGB, tight star); 0 = round-35 values
uniform float uBlade;   // r55: 1 = blade-only pass (for 8px gaussian soft glow)

const float PI = 3.14159265;

float3x3 rotX(float a){ float c=cos(a), s=sin(a); return float3x3(1,0,0, 0,c,-s, 0,s,c); }
float3x3 rotY(float a){ float c=cos(a), s=sin(a); return float3x3(c,0,s, 0,1,0, -s,0,c); }
float3x3 rotZ(float a){ float c=cos(a), s=sin(a); return float3x3(c,-s,0, s,c,0, 0,0,1); }
// rotation by th about unit axis a (columns = R*e_i)
float3x3 rotAxis(float3 a, float th){ float c=cos(th), s=sin(th), t=1.0-c;
    return float3x3(t*a.x*a.x+c, t*a.x*a.y+s*a.z, t*a.x*a.z-s*a.y,
                    t*a.x*a.y-s*a.z, t*a.y*a.y+c, t*a.y*a.z+s*a.x,
                    t*a.x*a.z+s*a.y, t*a.y*a.z-s*a.x, t*a.z*a.z+c); }

// width profile along u in [0,1]: rounded root, widest ~0.35, needle tip
// centred sheet: widest at the middle, needles at both ends, slightly asymmetric
float profile(float u){
    float m = sin(clamp(u,0.0,1.0)*PI);
    float oval = sqrt(max(1.0 - (2.0*u-1.0)*(2.0*u-1.0), 0.0));
    // drop: round head, thin tail: sin(pi t)^0.6 * (1 - 0.6 t)
    float drop = pow(m, 0.6) * (1.0 - 0.6*u) * 1.3;
    return mix(mix(pow(m, 0.7), pow(oval, 0.8), uListen), drop, uProc);   // listening: round tips   // ribbons: full lens-shaped middle, pointed tips
}

// r42 idle geometry: lens surface x=u*L, y=v*W0*sqrt(1-u^2), z=b*(u^2-0.3), 20 deg twist about the long axis; units = orb radius R
float sheetSdfIdle(float3 p, float b, out float2 uv){
    float L = 0.8; float W0 = 0.45;   // r51b: W0 0.45R
    float u = clamp(p.x / L, -1.0, 1.0);   // r45: centred lens
    float3 q = float3(p.x, p.y, p.z - b * (u*u - 0.3));
    float a = 0.1745 * u;
    float c = cos(a), s = sin(a);
    float2 yz = float2(c*q.y + s*q.z, -s*q.y + c*q.z);
    float w = W0 * pow(max(1.0 - u*u, 0.0), 0.6);   // r49: (1-u^2)^1.2, tips close to 0 // r52: ^0.6 round petals
    uv = float2(0.5 + 0.5*u, yz.x / max(w, 1e-4));
    float dz = abs(yz.y) - max(0.004, 1.25 / (0.45 * uSize.x * max(uScale, 0.05)));   // r51: slab at least 2.5 screen px thick so edge-on sheets are not dropped pixel by pixel (dotted lines)
    float dy = abs(yz.x) - w;
    float dx = abs(p.x) - L;
    return max(max(dz, dy), dx);
}

// signed-ish distance to sheet k in its local frame; also returns (u, v) in uv
float sheetSdf(float3 p, float twist, float bend, float ws, out float2 uv){
    if (uIdle > 0.5) return sheetSdfIdle(p / ws, bend, uv) * ws;   // r43: per-sheet size factor ws   // r42 idle: parametric lens
    float L = 1.05 * (1.0 - 0.3 * uListen);   // listening: shorter petals, aspect <= 2:1              // half length (shell radii): 85% of diameter x1.5
    float u = (p.x + L) / (2.0*L);
    // bend: centreline arcs in y, centred
    float xs = p.x / L;
    float yc = bend * (xs*xs - mix(0.33, 0.0, uIdle)) + uIdle * 0.233 * L * xs * abs(xs);   // r37 idle: edge-on rims cross the exact centre   // r41 idle: tips swept tangentially ~25 deg (S-curve)
    float3 q = float3(p.x, p.y - yc, p.z);
    // twist around the long axis
    float a = twist * (u - 0.5);
    float c = cos(a), s = sin(a);
    float2 yz = float2(c*q.y + s*q.z, -s*q.y + c*q.z);
    float w = mix(profile(clamp(u,0.0,1.0)), profile(0.5) * clamp(2.0 - 2.0 * abs(2.0*u - 1.0), 0.0, 1.0), uIdle) * 0.55 * ws;   // half width (shell radii)   // r41 idle: width tapers linearly from mid-sheet to 0 at the tips
    uv = float2(u, yz.x / max(w, 1e-4));
    float vv = clamp(yz.x / max(w, 1e-4), -1.0, 1.0);
    float dz = abs(yz.y - 0.18 * w * vv * vv) - 0.004;
    float dy = abs(yz.x) - w;
    float dx = max(-u, u - 1.0) * 2.0 * L;
    return max(max(dz, dy), dx);
}

// half width (sheet units) at the point p: same parametrisation as sheetSdf
float sheetHalfW(float3 p, float ws){
    if (uIdle > 0.5) { float uI = clamp(p.x / ws / 0.8, -1.0, 1.0); return ws * 0.45 * pow(max(1.0 - uI*uI, 0.0), 0.6); }   // r51b   // r49: match sheetSdfIdle   // r42 idle lens half width   // r50 // r52
    float L = 1.05 * (1.0 - 0.3 * uListen);
    float u = clamp((p.x + L) / (2.0*L), 0.0, 1.0);
    return mix(profile(u), profile(0.5) * clamp(2.0 - 2.0 * abs(2.0*u - 1.0), 0.0, 1.0), uIdle) * 0.55 * ws;   // r41: same taper as sheetSdf
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
    float sizeI = mix(1.0, 1.0 / 0.9, uIdle);   // r42 idle: object units = orb radius R (lens geometry is absolute)   // r38 idle: sheet radius (radial fade caps the outer edge at ~0.7R)
    float2 p = pS / (uScale * mix(0.8, 0.9, uProc) * sizeI);   // sheets kept within ~0.85R   // sheets at 0.9 stay inside the glass   // ribbons fill the glass
    p.y = -p.y;
    float3 ro = float3(0, 0, 5.85);                  // camera 2700/461
    float3 rd = normalize(float3(p * (1.0/5.85) * 5.85 / 5.85, -1.0));
    rd = normalize(float3(p / 5.85, -1.0));
    half3 acc = half3(0); half accA = 0.0;
    float r = length(pS);
    float rad0 = mix(0.78, 0.80, uProc) + 0.5 * uListen;
    float rad1 = mix(0.88, 0.97, uProc) + 0.5 * uListen;
    float radial = 1.0 - smoothstep(mix(rad0, 0.95, uIdle), mix(rad1, 1.0, uIdle), r);   // r42 idle: no radial opacity fade, lens shape closes the outline   // idle: melt into black before the glass rim
    float q1 = 1.0 - uProc;
    float starBoost = 1.0 + 3.0 * q1 * uSideK * exp(-(r/0.2)*(r/0.2));
    float dd6[7]; half4 cc6[7];   // r51c: up to 7 sheets (idle adds a half-side blue)
    for (int j = 0; j < 7; j++) { dd6[j] = -1.0; cc6[j] = half4(0); }
    for (int k = 0; k < 7; k++) {
        if (float(k) >= uCount) break;
        float fk = float(k);
        if (k == 6 && uIdle < 0.5) break;   // r51c: 7th sheet is idle-only
        float t = uTime;
        // AE: time*45+value / -time*30+value deg/s, counter-rotating groups; wiggle(0.2,10/15)
        float spin = (mod(fk,2.0) < 0.5 ? 45.0 : -30.0) * PI/180.0;
        float wig = (sin(t*1.257 + fk*1.9) * 0.6 + sin(t*0.71 + fk*3.1) * 0.4) * uFlip;
        float ws = k == 1 ? 1.3 : (k == 2 ? 1.05 : (k == 5 ? 0.8 : 1.0));   // blue = largest central lens
        if (k >= 3) ws *= 0.85;
        if (k == 0) ws *= 0.7;                              // green: thin sliver (edge-on cyan back to 1.0x)
        // idle: RGB each ~1/3 area (blue shrinks, red/green grow); extras stay smaller
        float wsI = k == 0 ? 0.5 : (k == 1 ? 0.62 : (k == 2 ? 1.95 : (k == 3 ? 0.55 : (k == 4 ? 1.75 : (k == 5 ? 0.5 : ws)))));   // r38: tuned to measured share   // r37: blue area -20%+ (tuned by measured RGB share), green larger
        ws = mix(ws, wsI, uIdle);
        ws *= (k == 1) ? mix(1.0, 0.7, uListen) : mix(1.0, 1.6, uListen);   // listening: petals x1.6, blue lens 0.7x
        // per-sheet X/Y/Z rates (deg/s), AE: time*30 / time*45 / -time*30 / -time*45
        float3 rate = k == 0 ? float3(30.0, -45.0, 30.0) : (k == 1 ? float3(-45.0, 30.0, -30.0) : (k == 2 ? float3(45.0, 30.0, -45.0)
                    : (k == 3 ? float3(-30.0, -45.0, 45.0) : (k == 4 ? float3(45.0, -30.0, 30.0) : float3(-45.0, 45.0, -30.0)))));
        float3 off = k == 0 ? float3(0.3, 0.2, 0.0) : (k == 1 ? float3(1.4, 2.1, 1.1) : (k == 2 ? float3(2.6, 0.9, 2.2)
                    : (k == 3 ? float3(0.9, 3.3, 4.0) : (k == 4 ? float3(3.7, 1.6, 0.5) : float3(5.1, 4.4, 2.9)))));
        float rateK = (k == 2) ? mix(1.0, 0.6, uIdle) : 1.0;   // r37 idle: red crossed edge-on at its peak normal speed (~28 deg/s @2.1 s) -> looked like a jump
        float3 ang = off + rate * (PI/180.0) * uSpinT * 0.5 * rateK + float3(wig*0.35, wig*0.25, -wig*0.2) * mix(1.0, (k == 2 ? 0.5 : 1.0), uIdle);
        // long-term near edge-on sheets (cyan, dark red): plane normal kept ~perpendicular to the view
        if (k == 3 || k == 4) ang.x = mix(1.5708 + 0.22 * sin(t * 0.6 + fk) + (k == 4 ? 3.1416 : 0.0), 0.5 * sin(t * 0.6 + fk), uListen);   // listening: face-on
        // blue: limit flip so its normal stays within ~50 deg of the view (|x|,|y| tilt <= 0.6 rad)
        if (k == 1) {
            float lim = 0.6;
            ang.x = mix(lim * sin(ang.x), ang.x, uIdle);
            ang.y = mix(lim * sin(ang.y), ang.y, uIdle);
        }
        // other non-edge-on sheets: tilt <= 1 rad per axis -> side <= 0.71, so only cyan/dark-red exceed 0.8
        // idle: full Euler so RGB actually go edge-on and take turns
        if (k == 0 || k == 2 || k == 5) {
            ang.x = mix(sin(ang.x), ang.x, uIdle);
            ang.y = mix(sin(ang.y), ang.y, uIdle);
        }
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
        if (uIdle > 0.001) {
            // r38 idle: every sheet folds slowly the same way about one shared axis (per-sheet axis within ~11 deg of it, <= 14 deg/s)
            float3 A = normalize(float3(0.15, 0.7, 0.7));
            float3 ax = normalize(A + 0.2 * float3(cos(fk*1.047), 0.0, sin(fk*1.047)));
            float tI = uSpinT / 0.6;   // spinT runs at the idle speed 0.6 -> real seconds
            float wD = k == 0 ? 14.0 : (k == 1 ? 11.0 : (k == 2 ? 13.0 : (k == 5 ? 12.0 : 0.0)));
            float th0 = k == 0 ? 0.0 : (k == 1 ? 2.356 : (k == 2 ? 3.1416 : 0.0));   // r39: phases so >=4 edge-on rims cross the centre
            float th = th0 + wD * (PI/180.0) * tI;
            if (k == 3 || k == 4 || k == 5) th = 0.2 * sin(0.3 * tI + fk);   // held near edge-on (base pose below), <= 3.5 deg/s
            float gam = k == 0 ? 0.2 : (k == 1 ? 1.3 : (k == 2 ? 2.4 : (k == 3 ? 0.75 : (k == 4 ? 1.85 : 2.95))));
            float3x3 RI = rotAxis(ax, th) * ((k == 3 || k == 4 || k == 5) ? rotX(1.5708) : float3x3(1,0,0, 0,1,0, 0,0,1)) * rotZ(gam);
            // r43c idle: long axis pitched 35..80 deg out of the screen plane, random azimuth (drift 3 deg/s); sheet rolls about its long axis <= 14 deg/s, phases staggered so 2-3 sheets are near edge-on (|n.v| < 0.3) at any time
            float pitI = k == 6 ? 0.1745 : k == 0 ? 0.2094 : (k == 1 ? 0.1745 : (k == 2 ? 0.1745 : (k == 3 ? 0.3491 : (k == 4 ? 0.1745 : 0.3054))));   // r50: blades k3/k5 pitch 20/17.5 deg so they project long (~0.75R) // r51e k6 pitch 10
            float psiI = (k == 6 ? 2.46 : (k == 0 ? 0.349 : (k == 1 ? 2.443 : (k == 2 ? 1.92 : (k == 3 ? 1.396 : (k == 4 ? 2.97 : 3.665)))))) + 0.2269 * tI;   // r51: azimuth drift 13 deg/s (was 3 deg/s; with rolls held, frames looked frozen) // r51e // r52: reds moved off the blue axes (lens axis repeats every 180 deg; 4.538/5.585 coincided with blues k3/k1) // r59: k6 axis ~60 deg from both other blades
            float rolI = (k == 6) ? (1.5708 + 0.26 * sin(0.32 * tI + 6.0)) : (k == 0 || k == 1 || k == 2 || k == 4) ? ((k == 0 ? 0.56 : (k == 1 ? -0.49 : (k == 2 ? 0.25 : -0.52))) + 0.04 * sin(0.3 * tI + fk)) : ((k == 3 ? 1.5708 : -1.5708) + 0.26 * sin((k == 3 ? 0.35 : 0.28) * tI + fk));   // r49: k0/k1/k2/k4 held 25-35 deg off the view (half-side faces); k3/k5 roll for edge blades, phases searched so both stay side-on >0.6 // r50: blades held edge-on (roll 90 +-15 deg, <=5 deg/s) -> always side-on > 0.75 // r51e k6 held ~31 deg off view // r52: red k2 near face-on (~14 deg) // r59: k6 becomes the 3rd edge-on blade (was half-side; only 2 sheets had side>0.75 in all 50 frames)
            float3 lI = float3(cos(pitI) * cos(psiI), cos(pitI) * sin(psiI), sin(pitI));
            float3 m2I = normalize(cross(lI, float3(0.0, 0.0, 1.0)));
            float3 m1I = cross(m2I, lI);
            float3 nI = cos(rolI) * m1I + sin(rolI) * m2I;
            RI = float3x3(lI, cross(nI, lI), nI);
            float3x3 Rm = R * (1.0 - uIdle) + RI * uIdle;
            float3 c0 = normalize(Rm[0]); float3 c1 = normalize(Rm[1] - dot(Rm[1], c0) * c0); float3 c2 = cross(c0, c1);
            R = float3x3(c0, c1, c2);
        }
        float3x3 Ri = float3x3(R[0][0],R[1][0],R[2][0], R[0][1],R[1][1],R[2][1], R[0][2],R[1][2],R[2][2]);
        // idle breathing: wiggle(0.2, 10/15) on twist and bend
        float br = sin(6.2832*0.2*t + fk*2.1), br2 = sin(6.2832*0.2*t + fk*1.3 + 1.0);
        float twist = (mix(1.4, 1.75, uIdle) + wig * 0.5 + 0.26*br + 5.0*uTangle) * q1 * (1.0 - 0.5 * uListen);   // idle: more ribbon folds
        float bend = (1.0 - 0.6 * uListen) * mix(0.49 * (mod(fk,2.0)<0.5 ? 1.0 : -1.0) + wig*0.11 + 0.28*br2 + 0.9*uTangle, 0.05 + 0.05 * fk, uProc);   // staggered 5..30% bend: petal/crescent
        ws = mix(ws, 1.1, uProc);   // x1.6 wider, width:length ~1:2
        bend = mix(bend, 0.06 + 0.008 * fk, uIdle);   // r46: bow 0.06..0.1
        offW *= 1.0 - uIdle;   // r42 idle: lens bow b = 0.15..0.25, every sheet through the centre
        ws = mix(ws, (k == 6 ? 0.8 : k == 0 ? 0.9 : (k == 1 ? 1.1 : (k == 2 ? 0.9 : (k == 3 ? 1.0 : (k == 4 ? 0.98 : 1.05))))), uIdle);   // r49: teal/blue/cyan 1.0-1.1, reds 0.9 / 0.98 // r51e k6 size 1.0 // r52: smaller fill sheets, ^0.6 lenses over-covered (60%)
        offW += uIdle * (k == 6 ? 0.05 : k == 0 ? 0.08 : (k == 1 ? -0.10 : (k == 2 ? 0.06 : (k == 3 ? -0.04 : (k == 4 ? 0.10 : -0.07))))) * (R * float3(1.0, 0.0, 0.0));   // r50 idle: fixed per-sheet shift along the long axis within +-0.1R // r51e
        if (k == 1) offW += float3(0.0, 0.0, mix(0.22, 0.0, uIdle) * q1);   // idle: less camera bias so RGB take turns
        if (k == 2) offW += float3(0.0, 0.0, 0.25 * uIdle);   // r52: face-on red sits in front
        float3 nW = R * float3(0, 0, 1);
        float sideS = 1.0 - abs(nW.z);                    // overall sheet normal vs view
        float pxW = 1.0 / (0.45 * uSize.x * uScale * mix(0.8, 0.9, uProc) * sizeI * sc) * 1.5;   // AA transition ~1.5 screen px
        float3 o = Ri * ((ro - offW) / sc); float3 d = Ri * rd;
        float tt = length(o) - 1.5; float2 uv = float2(0); bool hit = false; float minD = 1e3; float2 nearUv = float2(0); float nearT = 0.0; float aaK = 1.0;
        for (int i = 0; i < 220; i++) {
            if (i >= 128 && uListen < 0.01 && uIdle < 0.01) break;   // r40 idle: full 220 steps (grazing rays)
            float3 pos = o + d*tt;
            float dd = sheetSdf(pos, twist, bend, ws, uv);
            if (dd < minD) { minD = dd; nearUv = uv; nearT = tt; }
            if (dd < mix(mix(0.0005, 0.003, uListen), 0.0015, uIdle)) { hit = true; break; }   // listening: grazing rays no longer crawl out of iterations
            tt += max(dd * mix(mix(0.4, 0.3, uListen), 0.25, uIdle), mix(0.0012, 0.0005, uIdle));   // r40 idle: no stepping over the thin slab (hatch streaks)   // listening: finer march, no ragged silhouettes
            if (tt > length(o) + 1.5) break;
        }
        half3 baseC = sheetColor(k).rgb;
        if (k == 0) baseC = mix(baseC, half3(0.315, 0.72, 0.666), half(uIdle));   // r38: green +15 deg toward cyan, sat -25%
        if (k == 5) baseC = mix(baseC, half3(0.09, 0.32, 0.30), half(uIdle));
        if (k == 2) baseC = mix(baseC, half3(1.0, 0.231, 0.188), half(uIdle));     // r53: #FF3B30 hue ~3 deg
        if (k == 1) baseC = mix(baseC, half3(0.184, 0.42, 1.0), half(uIdle));     // r39: #2F6BFF
        if (k == 4) baseC = mix(baseC, half3(0.62, 0.17, 0.25), half(uIdle));
        if (uIdle > 0.5) { if (k == 3) baseC = half3(0.184, 0.42, 1.0); if (k == 4) baseC = half3(1.0, 0.231, 0.188); }   // r53: second red #FF3B30
        if (uIdle > 0.5 && k == 5) baseC = half3(0.235, 0.784, 0.94);   // r46: sixth sheet cyan #3CC8F0
        if (uIdle > 0.5 && k == 6) baseC = half3(0.184, 0.42, 1.0);   // r51e: 7th sheet blue #2F6BFF
        half4 col = half4(pow(baseC, half3(2.2)), 1);
        col.rgb = mix(col.rgb, half3(0.62, 0.62, 0.64), half(uGrey));   // desaturate to grey-white
        // analytic AA: near-miss within ~1 px still contributes with fractional coverage
        if (!hit && uIdle < 0.5 && minD < pxW * mix(1.0, 2.0, uIdle) && uPass < 0.5 && uProc < 0.01) { hit = true; tt = nearT; aaK = 1.0 - minD / (pxW * mix(1.0, 2.0, uIdle)); }   // r50m idle: wider near-miss window so edge-on blades are not dropped by the march (dotted white line). // r58: idle main pass takes no near-miss hits (they were scattered grazing samples beside edge-on blades = blue dotted residual); only the blade core pass keeps them so the core line stays continuous // r58b: blade pass also drops near-miss (its a = coreK override discarded aaK, so grazing near-misses drew a white dotted twin line); 2.5px min slab already keeps blades continuous
        if (hit && (uListen > 0.01 || uIdle > 0.01)) {
            // refine the hit depth (stable depth order where petals intersect; removes notched intersection lines)
            for (int r2 = 0; r2 < 4; r2++) { float2 uvR; tt += sheetSdf(o + d*tt, twist, bend, ws, uvR) * 0.9; }
        }
        if (hit) {
            float3 pos = o + d*tt; float2 tmp;
            float e = 0.0012;
            float3 n = normalize(float3(
                sheetSdf(pos+float3(e,0,0),twist,bend,ws,tmp)-sheetSdf(pos-float3(e,0,0),twist,bend,ws,tmp),
                sheetSdf(pos+float3(0,e,0),twist,bend,ws,tmp)-sheetSdf(pos-float3(0,e,0),twist,bend,ws,tmp),
                sheetSdf(pos+float3(0,0,e),twist,bend,ws,tmp)-sheetSdf(pos-float3(0,0,e),twist,bend,ws,tmp)));
            float ndv = abs(dot(n, -d));
            float fres = mix(pow(1.0 - ndv, 4.0), pow(sideS, 4.0), uIdle);   // r40 idle: sheet-level sheen, no per-pixel slab-normal banding
            // feathered ring texture: transparent middle, colour toward one edge (AE inverted circle mask)
            float side = clamp(uv.y*0.5+0.5, 0.0, 1.0);
            float ring = 0.35 + 0.65*pow(side, 1.5);
            float rim = smoothstep(0.86, 1.0, abs(uv.y)) * step(0.0, uv.y);
            // depth fog: far half fades to black (AE fog start 2700, range 1000)
            float z = (R * pos).z;
            float fog = pow(clamp(0.4 + 0.6*z/0.8, 0.0, 1.0), 1.6);   // stronger depth fade
            float front = smoothstep(mix(-0.08, -0.5, uIdle), mix(0.12, 0.5, uIdle), z);                    // behind the sphere centre -> 0
            fog = mix(fog * mix(0.3 / 0.4, 1.0, front), 1.0, uProc);    // back halves ~30%
            fog = mix(fog, 0.6 + 0.4 * fog, uIdle);   // r47t idle: depth fog floor 0.6 - the far tips of the steep blue/cyan sheets faded to black and read as blunt, rounded ends
            float rimFront = mix(front, 1.0, uProc);
            float centre = 1.0 - smoothstep(0.15, 1.0, abs(2.0*uv.x - 1.0));
            float body = (0.18 + 0.82*centre) * (1.0 - fres) * (0.5 + 0.5*ring);
            float thinRim = pow(1.0 - ndv, 9.0);
            if (uPass < 0.5) {
                // v: 0 at the rim (bright, hard) side .. 1 at the far edge (dark, feathered 15%)
                float v = 1.0 - side;
                float rA = r / max(uScale, 0.05);
                float briV = mix(mix(0.95, 0.3, v), mix(1.0, 0.75, abs(uv.y)), uIdle);   // r51 idle: symmetric across the width (was dark on one half)
                float coreR = mix(0.13, 0.08, uIdle);
                float coreTerm = mix(0.5, 0.20, uIdle) * uSideK * exp(-(rA/coreR)*(rA/coreR));
                float bri = briV * (1.0 - 0.75 * pow(clamp((1.0 - uSideK) / 0.25, 0.0, 1.0), 0.5) * exp(-(rA/0.35)*(rA/0.35))) * (1.0 + 0.8 * (coreTerm + 0.0)) * mix(1.0 + mix(0.5, 1.25, uIdle) * fres, 1.0, uBlend);   // idle: stronger grazing sheen
                float a = 0.9 * (1.0 - smoothstep(0.97, 1.0, abs(uv.y))) * (0.75 + 0.25*centre) * fog;   // solid body, ~3% feather
                // solid hard-edged oval with a slight soft edge
                // feathered white disc -> soft oval when flat
                float eo = 1.0 - smoothstep(0.93, 1.0, abs(uv.y));   // nearly hard edge
                bri = mix(bri, mix(mix(0.5, 0.75, uMove), 0.8, uGather), uProc);   // 0.5 while shrinking in place / gathered, below the glow threshold
                a = mix(a, 0.9 * eo, uProc);
                // depth layer split: world z of the hit (camera at +z)
                float zw = (R * (pos * sc)).z + offW.z;
                float wf = smoothstep(mix(-0.08, -0.75, uIdle), mix(0.08, -0.35, uIdle), zw);   // r39 idle: only the far back gets depth blur, wide transition (no radial stripes)   // r38 idle: no hard radial cut at z=0 (fan-blade stripes)
                if (uLayer > 0.5) a *= (uLayer < 1.5 ? wf : 1.0 - wf);
                bri *= mix(1.0, mix(0.5, 1.0, wf), uIdle);   // r54: far-layer brightness 0.5
                float dEdgeR = (1.0 - abs(uv.y)) * sheetHalfW(pos, ws); float faceShade = mix(1.0, 0.3, smoothstep(0.0, 0.12, dEdgeR)); bri *= mix(1.0, faceShade, uIdle);   // r54: face interior 0.45 rising to 1.0 within 0.08R of the contour; blades keep full // r55: interior 0.3, rim 1.0 over 0.12R // r56: side-on sheets also get face shading
                float rS = r / max(uScale, 0.05);   // radius relative to orb size (small appear orb is not all 'centre')
                float edgeOnly = mix(1.0, smoothstep(0.45, 0.95, abs(uv.y)), uRes);
                float bR = mix(0.45, 0.12, uIdle);
                bri *= 1.0 + mix(2.5, 4.6, uIdle) * q1 * edgeOnly * pow(sideS, 4.0) * exp(-(rS/bR)*(rS/bR)) * (1.0 - uIdle);   // idle: tighter star from side-on edges   // r45e idle: centre boost only on the edge line
                float whiteR = 1.0 - smoothstep(0.25, 0.55, rS);   // r55: blade white within 0.25R
                float sideP = mix(sideS, 1.0 - ndv, uIdle);   // r42 idle: side-on from the surface normal vs the view ray
                float s4 = pow(clamp(sideP, 0.0, 1.0), 4.0);
                float dEdgePx = (1.0 - abs(uv.y)) * sheetHalfW(pos, ws) / max(pxW / 1.5, 1e-6);   // r46: analytic distance to the sheet edge in pixels   // r50e: pxW is 1.5 screen px, so divide by pxW/1.5 to get true pixels
                float fwE = 1.0;   // r50e: Skia runtime SkSL has no fwidth/dFdx; dEdgePx is already in screen pixels (divided by pxW), so the AA width is a constant 1 px
                float lineCore = 1.0 - smoothstep(2.0 - 0.5 * fwE, 2.0 + 0.5 * fwE, dEdgePx);   // r50e: fixed 2 px core, fwidth + smoothstep AA
                float lineSoft = exp(-(dEdgePx * dEdgePx) / 32.0);   // r54: ~8 px same-colour soft glow
                float edge6 = mix(lineSoft * 0.4, 1.0, lineCore) * smoothstep(0.6, 0.7, sideS) * (1.0 - uIdle);   // r46: only side-on sheets get the analytic bright line   // r50d: gate the blade line by the whole-sheet side-on value; the per-pixel slab normal flips at every silhouette pixel and drew dotted white outlines on face-on sheets // r52: idle off - the edge line keyed on distance-to-rim only fires on scattered ray hits along edge-on sheet silhouettes = the dotted white lines // r54: idle on again (8 px soft); min-slab 2.5 px keeps hits continuous // r55: idle off again (no dotted residual)
                bri *= mix(1.0, 1.0 + 3.0 * q1 * edge6 * whiteR, uIdle);   // r46: boost only the analytic edge line near the centre
                float bladeK = 0.0;   // r56: no whole-sheet blade whitening/boost in the main scene (core line lives in the blade pass)
                a *= aaK * uBodyA;
                // listening: inner edge coverage over ~1.5 px (distance to the petal edge in pixels)
                float wE = sheetHalfW(pos, ws);
                float aaIn = clamp((1.0 - abs(uv.y)) * wE / pxW, 0.0, 1.0);
                a *= mix(1.0, aaIn, uListen);
                if (k == 1) a *= mix(1.0, 0.8 / 0.9, uListen);   // blue alpha ~0.8 in listening
                bri *= 1.0 + 0.25 * uListen;
                if (k == 0) bri *= mix(1.0, 1.3, uIdle);   // r37 idle: green x1.3
                if (k == 5) bri *= mix(1.0, 1.0, uIdle);   // r46: sixth sheet is cyan, no dark-green boost
                if (k == 2) { bri *= mix(mix(0.8, 1.0, uProc), 1.0, uIdle); a *= mix(0.7 / 0.9, 1.0, uProc); }   // red: darker, alpha 0.7 (idle: equal RGB)
                bri *= mix(1.0, mix(1.0, 0.5, smoothstep(0.6, 0.9, sideS) * (1.0 - wf)), q1);   // edge-on sheets behind centre x0.5
                float gR = mix(0.5, 0.12, uIdle);
                bri *= 1.0 + mix(0.8, 0.32, uIdle) * q1 * exp(-(rS/gR)*(rS/gR));                                   // idle: less circular centre wash
                bri *= mix(1.0, mix(1.0, 0.7, smoothstep(0.7, 1.0, abs(uv.y))), q1);           // darker toward sheet boundaries
                // idle silk: face-on ~0.38, side-on rim ~0.95; fade inward from bright edge; outer melts to black
                float aOrient = mix((k == 2 ? mix(0.5, 0.85, uIdle) : 0.5), 1.0, pow(clamp(sideP, 0.0, 1.0), 0.85));   // r53: face-on red opacity 0.85
                float aInward = mix(mix(1.0, 0.22, pow(v, 0.6)), 1.0, uIdle);   // r51 idle: the one-sided fade made half of each lens ~invisible, so the visible shape was a half-lens cut along its straight centreline (the straight edges / hexagram outline)
                float aEdge = clamp((1.0 - abs(uv.y)) * sheetHalfW(pos, ws) / (pxW / 1.5), 0.0, 1.0);   // r39: feather 1 px   // r37 idle: edge feather ~1.5 px
                float aSilk = aOrient * aInward * aEdge * fog * aaK * uBodyA;   // r42 idle: no radial opacity fade
                a = mix(a, aSilk, uIdle);
                float corePx = abs(uv.y) * sheetHalfW(pos, ws) * abs(nW.z) / max(pxW / 1.5, 1e-6); float uAx = abs(2.0 * uv.x - 1.0); float coreHW = mix(2.0, 0.75, uAx); float coreK = smoothstep(0.75, 0.9, sideS) * (1.0 - smoothstep(coreHW - 0.5, coreHW + 0.25, corePx)); if (uBlade > 0.5) { a = coreK; bri = mix(2.5, 1.5, smoothstep(0.1, 0.25, rS)) * mix(1.0, 0.8, uAx); }   // r56: blade pass keeps only the projected spine of sheets with side > 0.85, <= 3 px wide // r57: soft side fade 0.75-0.9; width 4px at centre -> 1.5px at tips, brighter at centre // r58: core 2.0 within 0.1R, back to 1.5 by 0.25R // r59: centre 2.5
                bri = min(bri, mix(1e3, 1.1, uRes));   // result: no fully white sheet bodies
                // edge-on blades go white; pre-cancel the final x1.3 saturation by the same amount
                float wR = mix(0.28, 0.12, uIdle);   // r37: white star radius 12% R
                float wm = (1.0 - 0.6 * uListen) * mix(0.6, 1.0, uIdle) * q1 * edgeOnly * pow(sideS, 4.0) * exp(-(rS/wR)*(rS/wR)) * mix(1.0, mix(0.15, 1.0, 1.0 - v), uIdle);
                wm = mix(0.0, bladeK * whiteR, uIdle);   // r55: white on side-on blades within 0.25R (bladeK defined above)
                float faceW = uIdle * 0.5 * (1.0 - smoothstep(0.0, 0.15, rS));   // r47: sheet faces within 0.15R mix toward white, max 0.5
                if (uDebug > 0.5) { wm = 0.0; col.rgb = (k == 0 || k == 5) ? half3(0,1,0) : ((k == 2 || k == 4) ? half3(1,0,0) : half3(0,0,1)); }
                col.rgb = mix(col.rgb, half3(1.0), half(max(wm, faceW)));
                col.rgb = mix(col.rgb, half3(0.498, 0.902, 1.0), half(uIdle * bladeK * (1.0 - whiteR)));   // r55: outer blade -> bright cyan #7FE6FF
                half lc = dot(col.rgb, half3(0.2126, 0.7152, 0.0722));
                col.rgb = max(mix(half3(lc), col.rgb, half(mix(1.0, 1.0/1.3, wm))), half3(0));
                if (uBlade > 0.5) col.rgb = mix(half3(0.498, 0.902, 1.0), half3(1.0), half(whiteR));   // r56: core line white within 0.25R, bright cyan #7FE6FF outside
                half4 c4 = half4(col.rgb * half(bri), half(a));
                if (uBlend > 0.5) {
                    half3 e = min(c4.rgb * c4.a, half3(1.0));
                    acc = acc + e - acc * e;          // Screen
                } else {
                    for (int j = 0; j < 7; j++) { if (j == k) { dd6[j] = tt; cc6[j] = c4; } }
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
        for (int s = 0; s < 7; s++) {
            float dm = -1.0; float bi = -1.0;
            for (int j = 0; j < 7; j++) { if (dd6[j] > dm) { dm = dd6[j]; bi = float(j); } }
            if (dm < 0.0) break;
            half4 c4 = half4(0);
            for (int j = 0; j < 7; j++) { if (float(j) == bi) { c4 = cc6[j]; dd6[j] = -1.0; } }
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
    val grey: Float = -1f, val listen: Float = 0f, val bodyA: Float = 1f, val edge: Float = 0f, val coreScale: Float = -1f, val res: Float = 0f,
    val idle: Float = 1f,
)

internal fun renderSheetStills(
    outDir: Path, count: Int, fps: Double, seconds: Double, size: Int = 870,
    prefix: String = "s",
    paramsAt: (Int, Double) -> OrbParams = { _, t -> OrbParams(spinT = 0.6f * t.toFloat(),   // r38: same idle speed as the demo (0.6)
        sideK = (System.getenv("SIDEK") ?: "1.25").toFloat(), core = (System.getenv("CORE") ?: System.getenv("SIDEK") ?: "1.25").toFloat()) },
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
        uniform float uOver;
        half4 main(float2 c) {
            float rr = length(c - 0.5*uSize) / (0.45*uSize.x);
            half3 x = max(img.eval(c).rgb, half3(0)) * 0.765;   // exposure -15%
            x *= half((1.0 - smoothstep(1.0 + 0.5 * uOver, 1.08 + 0.5 * uOver, rr)) * uFade);
            x = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
            half l = dot(x, half3(0.2126, 0.7152, 0.0722));
            x = clamp(mix(half3(l), x, 1.3), 0.0, 1.0);
            return half4(pow(x, half3(1.0 / 2.2)), 1);
        }
    """)
    val glowFx = RuntimeEffect.makeForShader("""
        uniform float2 uC; uniform float uR; uniform float uG; uniform float uE;   // r57: falloff exponent
        half4 main(float2 c) { float x = length(c - uC) / uR; return half4(half3(uG * exp(-x * x * uE)), 1); }
    """)
    val spillFx = RuntimeEffect.makeForShader("""
        uniform shader img; uniform float2 uSize; uniform float uK; uniform float uSc; uniform float uFall;
        half4 main(float2 c) {
            float r = length(c - 0.5*uSize) / (0.45*uSize.x) / max(uSc, 0.05);   // relative to orb size
            half3 x = max(img.eval(c).rgb, half3(0));
            half l = min(dot(x, half3(0.2126, 0.7152, 0.0722)) * 3.0, 1.0);
            half w = half(uK * (1.0 - smoothstep(0.0, uFall, r)));
            return half4(half3(l * w), 1);
        }
    """)
    val fresnelFx = RuntimeEffect.makeForShader("""
        uniform float2 uC; uniform float uR; uniform float uA; uniform float uPx;
        half4 main(float2 c) {
            float2 d = c - uC; float r = length(d) / uR;
            float band = smoothstep(0.92, 1.0, r) * (1.0 - smoothstep(1.0 - uPx, 1.0, r));
            float t = clamp(dot(d / max(length(d), 1e-3), float2(0.7071, 0.7071)) * 0.5 + 0.5, 0.0, 1.0);   // r55: 0=upper-left (red), 1=lower-right (blue)
            half3 col = mix(half3(1.0, 0.231, 0.361), half3(0.231, 0.482, 1.0), half(t));
            return half4(col * half(band * band * uA), 1);
        }
    """)
    Files.createDirectories(outDir)
    val n = (fps * seconds).toInt().coerceAtLeast(1)
    for (i in 0 until n) {
        val t = i / fps
        val prm = paramsAt(i, t)
        val id = prm.idle.coerceIn(0f, 1f)
        if (i < (System.getenv("SKIP") ?: "0").toInt()) continue   // resume a partial render
        if (i > (System.getenv("END") ?: "999999").toInt()) break
        fun pass(id: Float, layer: Float = 0f, blade: Float = 0f): org.jetbrains.skia.Image {
            // listening: 2x2 supersampled body pass (clean petal edges), downsampled to size
            val ss = 1   // 2x2 supersampling tried in r35: did not remove the petal notches, disabled (4x cost)
            val sz = size * ss
            val b = RuntimeShaderBuilder(effect)
            b.uniform("uSize", sz.toFloat(), sz.toFloat())
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
            b.uniform("uGrey", if (prm.grey >= 0f) prm.grey else prm.proc)
            b.uniform("uListen", prm.listen)
            b.uniform("uBodyA", prm.bodyA)
            b.uniform("uRes", prm.res)
            b.uniform("uIdle", prm.idle)
            b.uniform("uDebug", (System.getenv("DEBUG_ID") ?: "0").toFloat())
            b.uniform("uBlade", blade)
            val surf = Surface.makeRaster(org.jetbrains.skia.ImageInfo(sz, sz, org.jetbrains.skia.ColorType.RGBA_F16, org.jetbrains.skia.ColorAlphaType.PREMUL))
            surf.canvas.clear(if (layer > 0f) Color.TRANSPARENT else Color.BLACK)
            surf.canvas.drawPaint(Paint().apply { this.shader = b.makeShader() })
            if (ss == 1) return surf.makeImageSnapshot()
            val big = surf.makeImageSnapshot()
            val small = Surface.makeRaster(org.jetbrains.skia.ImageInfo(size, size, org.jetbrains.skia.ColorType.RGBA_F16, org.jetbrains.skia.ColorAlphaType.PREMUL))
            small.canvas.clear(if (layer > 0f) Color.TRANSPARENT else Color.BLACK)
            small.canvas.drawImageRect(big, org.jetbrains.skia.Rect.makeWH(sz.toFloat(), sz.toFloat()), org.jetbrains.skia.Rect.makeWH(size.toFloat(), size.toFloat()),
                org.jetbrains.skia.SamplingMode.LINEAR, Paint().apply { blendMode = BlendMode.SRC }, true)
            return small.makeImageSnapshot()
        }
        val radius = size * 0.45f
        val cx = size / 2f
        fun f16() = Surface.makeRaster(org.jetbrains.skia.ImageInfo(size, size, org.jetbrains.skia.ColorType.RGBA_F16, org.jetbrains.skia.ColorAlphaType.PREMUL))
        // Depth blur: front half sharp, sheets behind the centre blurred ~4% R (idle only; drops stay sharp).
        val bodyImg = if (prm.proc < 0.99f) {
            val front = pass(0f, 1f); val backImg = pass(0f, 2f)
            val bs = f16(); bs.canvas.clear(Color.BLACK)
            val bb = mixF(radius * 0.04f, 3f * size / 870f, id) * (1f - prm.proc) + 0.01f   // r51f idle: back-layer blur ~3 px
            bs.canvas.drawImage(backImg, 0f, 0f, Paint().apply { imageFilter = ImageFilter.makeBlur(bb, bb, FilterTileMode.DECAL); blendMode = BlendMode.SRC_OVER })   // r54: OVER not PLUS so back RGB is not added under the front
            bs.canvas.drawImage(front, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC_OVER })   // front sharp, OVER the blurred back
            bs.makeImageSnapshot()
        } else pass(0f)
        val k = size / 1740f
        // 1) HDR scene: body (OVER in shader, slight soften) + sharp rim + HDR core.
        val scene = f16()
        val c = scene.canvas
        c.clear(Color.BLACK)
        c.drawImage(bodyImg, 0f, 0f, Paint().apply {
            imageFilter = ImageFilter.makeBlur(mixF(0.6f, 0.001f, id), mixF(0.6f, 0.001f, id), FilterTileMode.DECAL)   // r37 idle: front sheets blur 0
            blendMode = if (id > 0.5f) BlendMode.SRC_OVER else BlendMode.PLUS   // r54 idle: body OVER (shader already depth-sorted OVER); keep PLUS for non-idle HDR path
        })
        // r55 idle: blade soft glow = separate blade-only pass, 8px gaussian, PLUS back (no edge6 sampling)
        if (id > 0.5f) {
            val bladeImg = pass(0f, 0f, 1f)
            val bPx = 8f * size / 870f
            c.drawImage(bladeImg, 0f, 0f, Paint().apply { blendMode = BlendMode.PLUS })   // r56: sharp core line once
            c.drawImage(bladeImg, 0f, 0f, Paint().apply {   // r56: soft glow = 8px blur x0.5, tinted bright cyan
                imageFilter = ImageFilter.makeBlur(bPx, bPx, FilterTileMode.DECAL)
                colorFilter = org.jetbrains.skia.ColorFilter.makeMatrix(org.jetbrains.skia.ColorMatrix(
                    0.083f, 0.083f, 0.083f, 0f, 0f, 0.150f, 0.150f, 0.150f, 0f, 0f, 0.167f, 0.167f, 0.167f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
                blendMode = BlendMode.PLUS
            })
        }
        // White spill: pure white (luminance of sheet coverage only, no hue), Screen, falls to 0 by 0.3R.
        run {
            val k0 = ((System.getenv("SPILL") ?: "1").toFloat() * mixF(0.3f, 0f, id) * prm.sideK * prm.sideK * (1f - prm.move) * (1f - smoothstepF(0f, 0.03f, prm.proc)) * (1f - prm.res)).coerceAtLeast(0f)   // idle: shrink circular white spill   // r44 idle: no luminance white spill (white only from side-on edges)
            if (k0 > 0.001f) {
                val sb = RuntimeShaderBuilder(spillFx)
                sb.child("img", bodyImg.makeShader())
                sb.uniform("uSize", size.toFloat(), size.toFloat()); sb.uniform("uK", k0); sb.uniform("uSc", prm.scale); sb.uniform("uFall", mixF(0.3f, 0.12f, id))
                c.drawPaint(Paint().apply { shader = sb.makeShader(); blendMode = BlendMode.SCREEN })
            }
        }
        // HDR core (peak ~4): additive passes, halo ~15% radius.
        for ((r, sig, reps) in listOf(
            Triple(radius * mixF(0.066f, 0.07f, id), radius * mixF(0.048f, 0.05f, id), if (id > 0.5f) 0 else 1),   // r59 idle: largest core layer removed
            Triple(radius * mixF(0.036f, 0.013f, id), radius * mixF(0.021f, 0.0055f, id), 2),
            Triple(radius * mixF(0.015f, 0.007f, id), radius * mixF(0.006f, 0.0028f, id), 4),
        )) {
          val total = mixF(1.3f, 0.2f, id)   /* r47 idle: additive centre, peak ~0.9 with the soft glow */ * 0.8f * reps * prm.core * (1f - kotlin.math.max(prm.move, prm.proc)) // r59: idle core strength 0.2
          var left = total
          while (left > 0.001f) {
            val a = left.coerceAtMost(1f); left -= a
            c.drawCircle(cx, cx, r * (if (prm.coreScale >= 0f) prm.coreScale else prm.scale.coerceIn(0.55f, 1.5f)), Paint().apply {
                color = ((a * 255).toInt() shl 24) or 0xFFFFFF
                blendMode = BlendMode.PLUS
                maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, sig)
            })
          }
        }
        // Wide soft glow ~0.5 R, strength ~0.35, fast falloff.
        run {
            val g = mixF(0.15f, 1.0f, id) * prm.core * (1f - kotlin.math.max(prm.move, prm.proc))   // r56: centre soft glow stronger (radius stays 0.16R) for ~4% white star // r57: idle strength 1.0 // r57b: idle strength 2.0 (cap) - 1.0 gave ~1.5% white // r58: back to 1.0
            if (g > 0.001f) {
                // radial gradient instead of a mask-blurred circle (the blur produced a faint square box at small sizes)
                val gs = (if (prm.coreScale >= 0f) prm.coreScale else 1f) * radius * mixF(0.5f, 0.16f, id)   // r55 idle: soft glow 0.16R
                val gb = RuntimeShaderBuilder(glowFx)
                gb.uniform("uC", cx, cx); gb.uniform("uR", gs); gb.uniform("uG", g); gb.uniform("uE", mixF(9f, 3f, id))   // r57 idle: slow falloff exp(-3x^2)
                c.drawPaint(Paint().apply { shader = gb.makeShader(); blendMode = BlendMode.PLUS })
            }
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
        // r45g idle: no HDR bloom halo (it smeared the edge crossings into a white blob)
        for ((sigma, alpha) in listOf(200f * k to 0.15f * (1f - id), 80f * k to 0.3f * (1f - id), 30f * k to 0.5f * (1f - id))) {
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
        tb.uniform("uOver", prm.listen)
        val fin = Surface.makeRasterN32Premul(size, size)
        fin.canvas.drawPaint(Paint().apply { shader = tb.makeShader() })
        val fc = fin.canvas
        // Shell glow after tonemap: 30%, ring 0.85..1.0 only.
        // Interior base #2D2C32, feathered edge; outside stays black.
        // Ball base: deep blue #0B1020 under everything; inner rim glow (0.8..1.0 R) from blurred sheet colour.
        val sh = prm.shell.coerceIn(0f, 1f)
        fc.drawCircle(cx, cx, radius * 0.985f, Paint().apply {
            val ir = mixF(5f, 0f, id).toInt()
            val ig = mixF(7f, 0f, id).toInt()
            val ib = mixF(13f, 0f, id).toInt()   // r46 idle: pure black #000000
            val ia = 255
            color = (((ia * sh).toInt().coerceIn(0, 255)) shl 24) or (ir shl 16) or (ig shl 8) or ib
            blendMode = BlendMode.PLUS
            if (id < 0.99f) maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.01f * (1f - id) + 0.001f)
        })
        val rimA = (sh * (1f - prm.move)).coerceIn(0f, 1f)
        if (rimA > 0.01f) {
            fc.saveLayer(null, Paint().apply { blendMode = BlendMode.PLUS; alpha = (mixF(55f, 38f, id) * rimA).toInt().coerceIn(0, 255) })   // r55 idle: outer halo peak ~0.15
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
        // r55 idle glass: soft fresnel, upper-left red / lower-right blue, peak 0.15
        if (id > 0.01f && sh > 0.01f) {
            val fb = RuntimeShaderBuilder(fresnelFx)
            fb.uniform("uC", cx, cx); fb.uniform("uR", radius); fb.uniform("uA", 0.15f * id * sh); fb.uniform("uPx", 1.5f / radius)
            fc.drawPaint(Paint().apply { shader = fb.makeShader(); blendMode = BlendMode.PLUS })
        }
        // Result: noticeably bright glass edge
        if (prm.edge > 0.01f) fc.drawCircle(cx, cx, radius * 0.975f, Paint().apply {
            mode = org.jetbrains.skia.PaintMode.STROKE; strokeWidth = 1.5f; isAntiAlias = true
            color = ((0x4C * prm.edge.coerceIn(0f, 1f)).toInt() shl 24) or 0x9FD8FF
            blendMode = BlendMode.PLUS
        })
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
internal val SheetDemoStates = listOf("Appear" to 1.0, "Idle" to 6.0, "Listening" to 2.5, "Processing" to 3.0, "Result" to 3.0, "Disappear" to 1.0)

internal fun renderSheetDemo(outDir: Path, fps: Double = 10.0) {
    data class Target(val speed: Float, val flip: Float, val scale: Float, val core: Float)
    val tg = mapOf(
        "Appear" to Target(1.0f, 1.0f, 1.0f, 1.0f), "Idle" to Target(0.6f, 1.0f, 1.0f, 1.0f),
        "Listening" to Target(1.3f, 1.3f, 1.2f, 1.2f), "Processing" to Target(1.0f, 1.0f, 1.0f, 1.0f),
        "Result" to Target(0.48f, 0.8f, 1.0f, 1.0f), "Disappear" to Target(1.0f, 1.0f, 0.0f, 0.0f),
    )
    val starts = SheetDemoStates.runningFold(0.0) { a, b -> a + b.second }
    var listenK = 0f; var burstS = 0f; var resK = 0f; var idleK = 0f
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
        val sideT = when (name) { "Listening" -> 1.0625f; "Processing" -> 0.9375f; else -> 1.25f }   // Result back to idle side-on params
        sideK += (sideT - sideK) * ease
        val voice = if (name == "Listening") {
            // VJshi: orb swells up to ~1.25x on loud syllables, never shrinks below rest size
            val syl = kotlin.math.abs(kotlin.math.sin(t * 12.6)).toFloat()
            val phrase = (0.5 + 0.5 * kotlin.math.sin(t * 2.1)).toFloat()
            val inn = smoothstepF(0f, 0.3f, tE) * (1f - smoothstepF(2.2f, 2.5f, tE))
            0.25f * syl * phrase * inn
        } else 0f
        val burst = (voice - 0.12f).coerceAtLeast(0f) * 20f     // spin bursts on voice peaks
        burstS += (burst - burstS) * ease                        // smoothed: no one-frame jump at listening start
        spinT += (speed + burstS) * dt
        // ring angle uses state-local time: 0 when Processing is entered, continues through Result
        orbit = if (si >= 3) -(Math.PI * (t - starts[3])).toFloat() else 0f
        // staging: shape morph in place first, radial move ~0.2 s later
        val proc = when (name) {
            "Processing" -> smoothstepF(0f, 0.4f, tE)   // r35: 0.4 s morph
            "Result" -> 1f - smoothstepF(0.4f, 1.0f, tE)       // drops grow back after the gather
            else -> 0f
        }
        val move = when (name) {
            "Processing" -> smoothstepF(0.05f, 0.45f, tE)
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
        var dScale = 0f; var dCore = 0f; var fade = 1f; var tangle = 0f; var bodyA = 1f; var coreScale = -1f
        when (name) {
            "Appear" -> {   // VJshi: dot -> full orb in ~0.6 s with a small overshoot, then settle by ~0.9 s
                // round 31: ~0.3 at 0.35 s, 1.0 at 0.85 s; core tied to size so the first frames are coloured, not a white blob
                // round 32: opacity rises with scale over 0.4 s; core held at 0.5 then full by 0.6 s; overshoot ~1.08 at 0.85 s
                val e = smoothstepF(0.05f, 0.85f, tE)
                val ov = 0.10f * kotlin.math.sin(Math.PI.toFloat() * smoothstepF(0.55f, 1.15f, tE))   // clearly visible ~1.08-1.1x at 0.85 s
                scale = 0.06f + 0.94f * e + ov; tangle = (1f - e) * (1f - e);   // r35: unwind early, no fast spin near the idle handoff fade = smoothstepF(0f, 0.1f, tE)
                core = 0.5f + 0.5f * smoothstepF(0.3f, 0.95f, tE)   // r35: spread ramp (was 0.4-0.6 s)
                bodyA = smoothstepF(0f, 0.6f, tE)
                coreScale = scale }   // core size follows the orb in appear only
            "Result" -> {
                // reverse morph (1.0 s), then white flash 1.6 -> settle and spring bounce to ~1.08
                // flash at the smallest (gathered) moment, then bounce as the orb regrows
                val r = (tE - 0.4f).coerceAtLeast(0f)
                if (tE >= 0.4f) {
                    val ex = kotlin.math.exp(-4f * r)
                    val sp = 1.0f + 0.12f * ex * kotlin.math.sin(7f * r)   // spring: peak ~1.07 above idle, settles to 1.0
                    dScale = sp - scale
                }
            }
            "Disappear" -> {
                // VJshi: shrink to a small bright dot over ~0.6 s, dot fades out by ~0.85 s
                // round 31: settle to a full small coloured ball, main shrink only in the last ~0.25 s
                val s1 = 1f - 0.4f * smoothstepF(0f, 0.6f, tE)   // r35: gentler shrink start
                val s2 = 1f - 0.9f * smoothstepF(0.65f, 0.9f, tE)
                dScale = s1 * s2 - scale
                fade = 1f - smoothstepF(0.8f, 0.9f, tE)   // r35: fully 0 by the last frame
                core = 1f - smoothstepF(0f, 0.85f, tE)   // r35: gentle core fade (exponential ease made a spike at the shrink start)
            }
        }
        val flash = if (name == "Result") 1.6f * kotlin.math.exp(-((tE - 0.4f) / 0.09f).let { it * it }) else 0f
        var sideOut = sideK
        if (name == "Processing") { val k0 = 1f - smoothstepF(0f, 0.3f, tE); sideOut = sideK * k0; core *= k0 }
        val regrow = 1f - proc
        val shell = if (name == "Processing") 1f - move
            else if (name == "Result") smoothstepF(0.6f, 1.0f, regrow)
            else if (name == "Disappear") fade * (1f - smoothstepF(0f, 0.6f, tE)) else fade
        // listening look eases in/out with the same 0.35 s constant
        resK += ((if (name == "Result" || name == "Disappear") 1f else 0f) - resK) * ease   // disappear keeps the result look: spill off
        listenK += ((if (name == "Listening") 1f else 0f) - listenK) * ease
        idleK += ((if (name == "Idle") 1f else 0f) - idleK) * ease
        // Result: drops recover their colour right after the gather flash instead of regrowing as grey silhouettes
        val grey = if (name == "Result") proc * (1f - smoothstepF(0f, 0.4f, tE)) else -1f   // r35: exit = reverse of the 0.4 s enter   // Result colourful from its start
        val edge = if (name == "Result") smoothstepF(0.4f, 0.8f, tE) * (1f - smoothstepF(2.2f, 3.0f, tE)) else 0f
        OrbParams(spinT, flip, (scale + dScale) * (1f + voice), (core + dCore) * (1f + voice), fade, sideOut, shell, proc, orbit, tangle, move, ring, gather, flash, grey, listenK, bodyA, edge, coreScale, resK, idleK)
    })
    Files.writeString(outDir.resolve("labels.txt"), labels.toString())
}

private fun smoothstepF(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t)
}

private fun mixF(a: Float, b: Float, t: Float): Float = a + (b - a) * t
