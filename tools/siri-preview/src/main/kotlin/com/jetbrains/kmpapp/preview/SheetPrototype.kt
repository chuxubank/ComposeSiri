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

const float PI = 3.14159265;

float3x3 rotX(float a){ float c=cos(a), s=sin(a); return float3x3(1,0,0, 0,c,-s, 0,s,c); }
float3x3 rotY(float a){ float c=cos(a), s=sin(a); return float3x3(c,0,s, 0,1,0, -s,0,c); }
float3x3 rotZ(float a){ float c=cos(a), s=sin(a); return float3x3(c,-s,0, s,c,0, 0,0,1); }

// width profile along u in [0,1]: rounded root, widest ~0.35, needle tip
// centred sheet: widest at the middle, needles at both ends, slightly asymmetric
float profile(float u){
    float m = sin(clamp(u,0.0,1.0)*PI);
    return pow(m, 0.75) * (0.85 + 0.15*u);
}

// signed-ish distance to sheet k in its local frame; also returns (u, v) in uv
float sheetSdf(float3 p, float twist, float bend, out float2 uv){
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
    float w = profile(clamp(u,0.0,1.0)) * 0.55;   // half width (shell radii)
    uv = float2(u, yz.x / max(w, 1e-4));
    float vv = clamp(yz.x / max(w, 1e-4), -1.0, 1.0);
    float dz = abs(yz.y - 0.18 * w * vv * vv) - 0.004;
    float dy = abs(yz.x) - w;
    float dx = max(-u, u - 1.0) * 2.0 * L;
    return max(max(dz, dy), dx);
}

half4 sheetColor(int k){
    if (k == 0) return half4(1.0, 0.176, 0.333, 1);   // #FF2D55
    if (k == 1) return half4(0.039, 0.518, 1.0, 1);   // #0A84FF
    return half4(0.0, 1.0, 0.8, 1);                   // #00FFCC
}

half4 main(float2 fc){
    float2 p = (fc - 0.5*uSize) / (0.45*uSize.x);   // ribbons fill the glass
    p.y = -p.y;
    float3 ro = float3(0, 0, 5.85);                  // camera 2700/461
    float3 rd = normalize(float3(p * (1.0/5.85) * 5.85 / 5.85, -1.0));
    rd = normalize(float3(p / 5.85, -1.0));
    half3 acc = half3(0);
    float r = length(p);
    float radial = 1.0 - smoothstep(0.85, 1.0, r);
    float starBoost = 1.0 + 3.0 * exp(-(r/0.3)*(r/0.3));
    float dA = -1.0; float dB = -1.0; float dC = -1.0;
    half4 cA = half4(0); half4 cB = half4(0); half4 cC = half4(0);
    for (int k = 0; k < 3; k++) {
        if (float(k) >= uCount) break;
        float fk = float(k);
        float t = uTime;
        // AE: time*45+value / -time*30+value deg/s, counter-rotating groups; wiggle(0.2,10/15)
        float spin = (mod(fk,2.0) < 0.5 ? 45.0 : -30.0) * PI/180.0;
        float wig = sin(t*1.257 + fk*1.9) * 0.6 + sin(t*0.71 + fk*3.1) * 0.4;
        // per-sheet X/Y/Z rates (deg/s), AE: time*30 / time*45 / -time*30 / -time*45
        float3 rate = k == 0 ? float3(30.0, -45.0, 30.0) : (k == 1 ? float3(-45.0, 30.0, -30.0) : float3(45.0, 30.0, -45.0));
        float3 off = k == 0 ? float3(0.3, 0.2, 0.0) : (k == 1 ? float3(1.4, 2.1, 1.1) : float3(2.6, 0.9, 2.2));
        float3 ang = off + rate * (PI/180.0) * t * 0.5 + float3(wig*0.35, wig*0.25, -wig*0.2);
        float3x3 R = rotZ(ang.z) * rotY(ang.y) * rotX(ang.x);
        float3x3 Ri = float3x3(R[0][0],R[1][0],R[2][0], R[0][1],R[1][1],R[2][1], R[0][2],R[1][2],R[2][2]);
        float twist = 1.4 + wig * 0.5;
        float bend = 0.35 * (mod(fk,2.0)<0.5 ? 1.0 : -1.0) + wig*0.08;
        float3 o = Ri * ro; float3 d = Ri * rd;
        float tt = 4.3; float2 uv = float2(0); bool hit = false; float minD = 1e3; float2 nearUv = float2(0);
        for (int i = 0; i < 72; i++) {
            float3 pos = o + d*tt;
            float dd = sheetSdf(pos, twist, bend, uv);
            if (dd < minD) { minD = dd; nearUv = uv; }
            if (dd < 0.0008) { hit = true; break; }
            tt += max(dd * 0.55, 0.002);
            if (tt > 7.5) break;
        }
        half4 col = half4(pow(sheetColor(k).rgb, half3(2.2)), 1);
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
            float centre = 1.0 - smoothstep(0.15, 1.0, abs(2.0*uv.x - 1.0));
            float body = (0.18 + 0.82*centre) * (1.0 - fres) * (0.5 + 0.5*ring);
            float thinRim = pow(1.0 - ndv, 9.0);
            if (uPass < 0.5) {
                // v: 0 at the rim (bright, hard) side .. 1 at the far edge (dark, feathered 15%)
                float v = 1.0 - side;
                float bri = mix(1.3, 0.35, v);
                float a = mix(0.9, 0.4, v) * (1.0 - smoothstep(0.85, 1.0, v)) * (0.6 + 0.4*centre) * fog;
                half4 c4 = half4(col.rgb * half(bri), half(a));
                if (k == 0) { dA = tt; cA = c4; } else if (k == 1) { dB = tt; cB = c4; } else { dC = tt; cC = c4; }
            } else {
                // first hit only; far side attenuated by fog^2; tips fade radially
                float rimK = thinRim * pow(1.0 - ndv, 4.0) * starBoost;
                half3 bright = mix(min(col.rgb * 1.35 + half3(0.12), half3(1.0)), half3(1.0), half(clamp((starBoost - 1.0) / 3.0, 0.0, 1.0)));
                acc += bright * half(clamp(rimK * 2.2, 0.0, 5.0) * fog * fog * (1.0 + 0.3 * (starBoost - 1.0)));
            }
        } else if (minD < 0.012) {
            // edge-on: sheet thinner than a pixel -> bright needle line
            float g = 1.0 - minD/0.012;
            if (uPass > 0.5) acc += min(col.rgb * 1.35 + half3(0.12), half3(1.0)) * half(g*g*0.7*starBoost);
        }
    }
    if (uPass < 0.5) {
        // back-to-front OVER (largest ray distance first)
        for (int s = 0; s < 3; s++) {
            float dm = max(dA, max(dB, dC));
            if (dm < 0.0) break;
            half4 c4;
            if (dm == dA) { c4 = cA; dA = -1.0; } else if (dm == dB) { c4 = cB; dB = -1.0; } else { c4 = cC; dC = -1.0; }
            acc = c4.rgb * c4.a + acc * (1.0 - c4.a);
        }
    }
    acc *= half(radial);
    return half4(acc, 1);
}
"""

internal fun renderSheetStills(outDir: Path, count: Int, fps: Double, seconds: Double, size: Int = 870) {
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
        half4 main(float2 c) {
            float rr = length(c - 0.5*uSize) / (0.45*uSize.x);
            half3 x = max(img.eval(c).rgb, half3(0)) * 0.9;
            x *= half(1.0 - smoothstep(1.0, 1.12, rr));
            x = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
            half l = dot(x, half3(0.2126, 0.7152, 0.0722));
            x = clamp(mix(half3(l), x, 1.3), 0.0, 1.0);
            return half4(pow(x, half3(1.0 / 2.2)), 1);
        }
    """)
    Files.createDirectories(outDir)
    val n = (fps * seconds).toInt().coerceAtLeast(1)
    for (i in 0 until n) {
        val t = i / fps
        fun pass(id: Float): org.jetbrains.skia.Image {
            val b = RuntimeShaderBuilder(effect)
            b.uniform("uSize", size.toFloat(), size.toFloat())
            b.uniform("uTime", t.toFloat())
            b.uniform("uCount", count.toFloat())
            b.uniform("uPass", id)
            val surf = Surface.makeRaster(org.jetbrains.skia.ImageInfo(size, size, org.jetbrains.skia.ColorType.RGBA_F16, org.jetbrains.skia.ColorAlphaType.PREMUL))
            surf.canvas.clear(Color.BLACK)
            surf.canvas.drawPaint(Paint().apply { this.shader = b.makeShader() })
            return surf.makeImageSnapshot()
        }
        val bodyImg = pass(0f)
        val rimImg = pass(1f)
        val radius = size * 0.45f
        val cx = size / 2f
        fun f16() = Surface.makeRaster(org.jetbrains.skia.ImageInfo(size, size, org.jetbrains.skia.ColorType.RGBA_F16, org.jetbrains.skia.ColorAlphaType.PREMUL))
        val k = size / 1740f
        // 1) HDR scene: body (OVER in shader, slight soften) + sharp rim + HDR core.
        val scene = f16()
        val c = scene.canvas
        c.clear(Color.BLACK)
        c.drawImage(bodyImg, 0f, 0f, Paint().apply {
            imageFilter = ImageFilter.makeBlur(radius * 0.06f, radius * 0.06f, FilterTileMode.DECAL)
            blendMode = BlendMode.PLUS
        })
        c.drawImage(rimImg, 0f, 0f, Paint().apply { blendMode = BlendMode.PLUS })
        // White spill along sheets near the centre (~30%).
        c.saveLayer(null, Paint().apply { blendMode = BlendMode.PLUS; alpha = 77 })
        c.drawImage(bodyImg, 0f, 0f, Paint().apply {
            colorFilter = org.jetbrains.skia.ColorFilter.makeMatrix(org.jetbrains.skia.ColorMatrix(
                0.33f, 0.33f, 0.33f, 0f, 0f, 0.33f, 0.33f, 0.33f, 0f, 0f,
                0.33f, 0.33f, 0.33f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        })
        c.drawCircle(cx, cx, radius * 0.28f, Paint().apply {
            blendMode = BlendMode.DST_IN
            maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.12f)
        })
        c.restore()
        // HDR core (peak ~4): additive passes, halo ~15% radius.
        for ((r, sig, reps) in listOf(
            Triple(radius * 0.11f, radius * 0.08f, 1),
            Triple(radius * 0.06f, radius * 0.035f, 2),
            Triple(radius * 0.025f, radius * 0.01f, 4),
        )) repeat(reps) {
            c.drawCircle(cx, cx, r, Paint().apply {
                color = 0xFFFFFFFF.toInt()
                blendMode = BlendMode.PLUS
                maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, sig)
            })
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
        val fin = Surface.makeRasterN32Premul(size, size)
        fin.canvas.drawPaint(Paint().apply { shader = tb.makeShader() })
        val fc = fin.canvas
        // Shell glow after tonemap: 30%, ring 0.85..1.0 only.
        fc.saveLayer(null, Paint().apply { blendMode = BlendMode.PLUS; alpha = 77 })
        fc.drawImage(bodyImg, 0f, 0f, Paint().apply {
            imageFilter = ImageFilter.makeBlur(radius * 0.3f, radius * 0.3f, FilterTileMode.DECAL)
        })
        fc.drawCircle(cx, cx, radius * 0.925f, Paint().apply {
            blendMode = BlendMode.DST_IN
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeWidth = radius * 0.15f
            maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.025f)
        })
        fc.restore()
        fc.drawCircle(cx, cx, radius, Paint().apply {
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeWidth = 1.2f
            color = 0x594D2D44
            isAntiAlias = true
        })
        val data = fin.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!
        Files.write(outDir.resolve("s%02d.png".format(i)), data.bytes)
    }
}
