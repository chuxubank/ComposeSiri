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
    if (k == 0) return half4(1.0, 0.176, 0.333, 1);   // #FF2D55
    if (k == 1) return half4(0.039, 0.518, 1.0, 1);   // #0A84FF
    return half4(0.235, 0.878, 0.91, 1);              // #3CE0E8
}

half4 main(float2 fc){
    float2 pS = (fc - 0.5*uSize) / (0.45*uSize.x);
    float2 p = pS / uScale;   // ribbons fill the glass
    p.y = -p.y;
    float3 ro = float3(0, 0, 5.85);                  // camera 2700/461
    float3 rd = normalize(float3(p * (1.0/5.85) * 5.85 / 5.85, -1.0));
    rd = normalize(float3(p / 5.85, -1.0));
    half3 acc = half3(0);
    float r = length(pS);
    float radial = 1.0 - smoothstep(0.80, 0.97, r);
    float starBoost = 1.0 + 3.0 * exp(-(r/0.3)*(r/0.3));
    float dA = -1.0; float dB = -1.0; float dC = -1.0;
    half4 cA = half4(0); half4 cB = half4(0); half4 cC = half4(0);
    for (int k = 0; k < 3; k++) {
        if (float(k) >= uCount) break;
        float fk = float(k);
        float t = uTime;
        // AE: time*45+value / -time*30+value deg/s, counter-rotating groups; wiggle(0.2,10/15)
        float spin = (mod(fk,2.0) < 0.5 ? 45.0 : -30.0) * PI/180.0;
        float wig = (sin(t*1.257 + fk*1.9) * 0.6 + sin(t*0.71 + fk*3.1) * 0.4) * uFlip;
        float ws = k == 2 ? 0.8 : 1.0;
        // per-sheet X/Y/Z rates (deg/s), AE: time*30 / time*45 / -time*30 / -time*45
        float3 rate = k == 0 ? float3(30.0, -45.0, 30.0) : (k == 1 ? float3(-45.0, 30.0, -30.0) : float3(45.0, 30.0, -45.0));
        float3 off = k == 0 ? float3(0.3, 0.2, 0.0) : (k == 1 ? float3(1.4, 2.1, 1.1) : float3(2.6, 0.9, 2.2));
        float3 ang = off + rate * (PI/180.0) * uSpinT * 0.5 + float3(wig*0.35, wig*0.25, -wig*0.2);
        float3x3 R = rotZ(ang.z) * rotY(ang.y) * rotX(ang.x);
        float3x3 Ri = float3x3(R[0][0],R[1][0],R[2][0], R[0][1],R[1][1],R[2][1], R[0][2],R[1][2],R[2][2]);
        float twist = 1.4 + wig * 0.5;
        float bend = 0.35 * (mod(fk,2.0)<0.5 ? 1.0 : -1.0) + wig*0.08;
        float3 o = Ri * ro; float3 d = Ri * rd;
        float tt = 4.3; float2 uv = float2(0); bool hit = false; float minD = 1e3; float2 nearUv = float2(0);
        for (int i = 0; i < 72; i++) {
            float3 pos = o + d*tt;
            float dd = sheetSdf(pos, twist, bend, ws, uv);
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
            float fog = clamp(0.55 + 0.45*z/0.9, 0.0, 1.0);
            float centre = 1.0 - smoothstep(0.15, 1.0, abs(2.0*uv.x - 1.0));
            float body = (0.18 + 0.82*centre) * (1.0 - fres) * (0.5 + 0.5*ring);
            float thinRim = pow(1.0 - ndv, 9.0);
            if (uPass < 0.5) {
                // v: 0 at the rim (bright, hard) side .. 1 at the far edge (dark, feathered 15%)
                float v = 1.0 - side;
                float bri = mix(1.3, 0.35, v) * (1.0 + 0.8 * exp(-(r/0.18)*(r/0.18)) + 3.0 * uSideK * pow(1.0 - ndv, 2.0) * exp(-(r/0.28)*(r/0.28))) * (1.0 + 0.5 * fres);
                float a = mix(0.95, 0.4, v) * (1.0 - smoothstep(0.85, 1.0, v)) * (0.6 + 0.4*centre) * fog;
                half4 c4 = half4(col.rgb * half(bri), half(a));
                if (k == 0) { dA = tt; cA = c4; } else if (k == 1) { dB = tt; cB = c4; } else { dC = tt; cC = c4; }
            } else {
                // first hit only; far side attenuated by fog^2; tips fade radially
                float vE = 1.0 - side;
                float edgeLine = 1.0 - smoothstep(0.0, 0.08, vE);
                float rimK = edgeLine * (0.35 + 0.65 * pow(1.0 - ndv, 2.0)) * starBoost;
                half3 bright = mix(min(col.rgb * 1.35 + half3(0.12), half3(1.0)), half3(1.0), half(clamp((starBoost - 1.0) / 3.0, 0.0, 1.0)));
                acc += bright * half(clamp(rimK * 2.2, 0.0, 5.0) * fog * fog * (1.0 - smoothstep(0.35, 0.75, r)));
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

internal data class OrbParams(
    val spinT: Float, val flip: Float = 1f, val scale: Float = 1f, val core: Float = 1f, val fade: Float = 1f,
    val sideK: Float = 1f, val shell: Float = 1f,
)

internal fun renderSheetStills(
    outDir: Path, count: Int, fps: Double, seconds: Double, size: Int = 870,
    prefix: String = "s",
    paramsAt: (Int, Double) -> OrbParams = { _, t -> OrbParams(spinT = t.toFloat()) },
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
            half3 x = max(img.eval(c).rgb, half3(0)) * 0.9;
            x *= half((1.0 - smoothstep(1.0, 1.08, rr)) * uFade);
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
        val prm = paramsAt(i, t)
        fun pass(id: Float): org.jetbrains.skia.Image {
            val b = RuntimeShaderBuilder(effect)
            b.uniform("uSize", size.toFloat(), size.toFloat())
            b.uniform("uTime", t.toFloat())
            b.uniform("uCount", count.toFloat())
            b.uniform("uPass", id)
            b.uniform("uSpinT", prm.spinT)
            b.uniform("uFlip", prm.flip)
            b.uniform("uScale", prm.scale)
            b.uniform("uSideK", prm.sideK)
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
        )) {
          val total = reps * prm.core
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
        fc.drawCircle(cx, cx, radius * 0.96f, Paint().apply {
            color = ((0x59 * prm.shell.coerceIn(0f, 1f)).toInt() shl 24) or 0x2D2C32
            blendMode = BlendMode.PLUS
            maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.03f)
        })
        fc.saveLayer(null, Paint().apply { blendMode = BlendMode.PLUS; alpha = (153 * prm.shell.coerceIn(0f, 1f)).toInt() })
        fc.drawImage(bodyImg, 0f, 0f, Paint().apply {
            imageFilter = ImageFilter.makeBlur(radius * 0.3f, radius * 0.3f, FilterTileMode.DECAL)
            // linear body is dark after a 30% blur: x3 gain, sRGB-ish lift
            colorFilter = org.jetbrains.skia.ColorFilter.makeMatrix(org.jetbrains.skia.ColorMatrix(
                3f, 0f, 0f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        })
        fc.drawCircle(cx, cx, radius * 0.875f, Paint().apply {
            blendMode = BlendMode.DST_IN
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeWidth = radius * 0.25f
            maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, radius * 0.025f)
        })
        fc.restore()
        fc.drawCircle(cx, cx, radius, Paint().apply {
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeWidth = 1.2f
            color = ((0x59 * prm.shell.coerceIn(0f, 1f)).toInt() shl 24) or 0x4D2D44
            isAntiAlias = true
        })
        val data = fin.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!
        Files.write(outDir.resolve("$prefix%03d.png".format(i)), data.bytes)
    }
}

/** Six-state demo in the preview: eased params per state, synthetic voice envelope. */
internal fun renderSheetDemo(outDir: Path, fps: Double = 10.0, perState: Double = 3.0) {
    data class Target(val name: String, val speed: Float, val flip: Float, val scale: Float, val core: Float, val fade: Float)
    val states = listOf(
        Target("Appear", 1.0f, 1.0f, 1.0f, 1.0f, 1f),
        Target("Idle", 1.0f, 1.0f, 1.0f, 1.0f, 1f),
        Target("Listening", 1.3f, 1.3f, 1.0f, 1.0f, 1f),
        Target("Processing", 2.4f, 1.8f, 0.85f, 0.8f, 1f),
        Target("Result", 0.6f, 0.6f, 1.04f, 1.25f, 1f),
        Target("Disappear", 1.0f, 1.0f, 0.0f, 0.0f, 1f),
    )
    var sideK = 1f; var speed = 1f; var flip = 1f; var scale = 0.0f; var core = 0f; var fade = 0f; var spinT = 0f
    val n = (states.size * perState * fps).toInt()
    val dt = (1.0 / fps).toFloat()
    val ease = 1f - kotlin.math.exp(-dt / 0.35f)   // ~0.35 s critically-damped-ish ease
    val labels = StringBuilder()
    renderSheetStills(outDir, 3, fps, n / fps, prefix = "d", paramsAt = { i, t ->
        val si = (t / perState).toInt().coerceIn(0, states.lastIndex)
        val st = states[si]
        val tE = (t - si * perState).toFloat()
        speed += (st.speed - speed) * ease; flip += (st.flip - flip) * ease
        scale += (st.scale - scale) * ease; core += (st.core - core) * ease; fade += (st.fade - fade) * ease
        val sideT = when (st.name) { "Listening" -> 0.85f; "Processing" -> 0.75f; "Result" -> 1.1f; else -> 1f }
        sideK += (sideT - sideK) * ease
        spinT += speed * dt
        val voice = if (st.name == "Listening") {
            val syl = kotlin.math.abs(kotlin.math.sin(t * 12.6)).toFloat()
            val phrase = (0.5 + 0.5 * kotlin.math.sin(t * 2.1)).toFloat()
            (syl * phrase - 0.5f) * 0.4f      // about +-20%
        } else 0f
        labels.append("$i ${st.name}\n")
        var dScale = 0f; var dCore = 0f; var outFade = fade
        when (st.name) {
            "Processing" -> dCore = 0.2f * kotlin.math.sin(2.0 * Math.PI * 1.5 * tE).toFloat()   // 0.6..1.0
            "Result" -> {
                dCore = 0.35f * (1f - smoothstepF(0f, 0.4f, tE))                                // flash 1.6 -> 1.25
                dScale = -0.04f * kotlin.math.exp(-1.5f * tE) * kotlin.math.cos(10f * tE)        // spring 1.0 -> ~1.07 -> 1.04
            }
            "Disappear" -> {
                // shrink to centre, then ~0.15 s core flash, then off
                // shell gone by 0.6 s; then a small 0.12 s flash centred at 0.76 s; then off
                dCore = (1f - kotlin.math.abs(tE - 0.76f) / 0.06f).coerceIn(0f, 1f) * 1.6f
                outFade = if (tE < 0.82f) 1f else 0f
            }
        }
        val shell = if (st.name == "Disappear") (outFade * (1f - smoothstepF(0f, 0.6f, tE))) else fade
        OrbParams(spinT, flip, (scale + dScale) * (1f + voice), (core + dCore) * (1f + voice), outFade, sideK, shell)
    })
    Files.writeString(outDir.resolve("labels.txt"), labels.toString())
}

private fun smoothstepF(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t)
}
