# siri.aep 制作流程提取（设计→shader 对照用）

来源：`/workspace/ComposeSiri-review/aep_dump.md`，原始导出 `/workspace/aep/flat.txt`（2024 行）、`props.txt`、`tree.txt`（自写 RIFX 解析器 `walk.py`）。
重要限制（先读）：
- **图层名、图层类型、混合模式、图层不透明度数值（ldta 块）没有解出来**。下表的"类型"是按图层上挂的效果推断的，标"推断"。
- Element 3D（`VIDEOCOPILOT 3DArray`）参数只有**数字 ID**（如 `-0192`），没有参数名对照表；模型网格、材质颜色、挤出/倒角、Twist/Bend 等在插件私有 blob 里，**全部未知**。
- 关键帧数值和缓动没解码（只知道"KEYFRAMED (n B)"）。Curves 曲线点未解码。
- 没找到 Misaka 的视频笔记文件（rg -i siri 在 md/txt 只命中 `designer/round36/brief.txt`、`compare20/README.txt` 等评审文件）。`/workspace/uploads` 不存在。B 站视频 `/workspace/videos/bili-siri.mp4` 本次没看。

## (5) 合成设置
| 项 | 值 |
|---|---|
| 合成 | 合成 1、合成 2、形状图层 1 合成 1、预合成 1、预合成 2、预合成 3 |
| 主合成尺寸 | 2560x1440（cdta），24 fps |
| 预合成尺寸 | 1440x1440，锚点 720,720 |
| 时长 | 未知 |
| 音频 | 旁白.mp3（tc Sound Keys 读它 → 驱动扰乱置换数量、缩放） |
| 摄像机 | 有 "自定义视图 1/2/3" 摄像机选项组（AE 默认视图）；真实摄像机图层、焦距、景深：未知 |
| 环境/反射灯组 | 6 个 3D 图层，锚点 1280,720，位置 (1280,720,±5000)、(−3720/6280,720,0)、(1280,−4280/5720,0) = 前后左右上下六方向；另 3 个位于 (−2275.556/1280/4835.556, −2835.556, −3555.556)，缩放 1。在 预合成 1、2、3 里各出现一套 |
| 材质选项（所有图层） | Glossiness 100，Index of Refraction 1，Appears in Reflections 开 |

## (1) 图层列表（按 flat.txt 中出现顺序；名称/混合模式未解出）
| # | 所在合成 | 类型（推断） | 关键内容 | 混合模式 | 不透明度 |
|---|---|---|---|---|---|
| L1 | 合成 2 | 带蒙版图层 | Mask 关键帧形状，**Mask Feather 1000,1000**，Mask Offset 关键帧 | 未知 | 未知 |
| L2 | 形状图层 1 合成 1 | 形状图层 "形状图层 1" | 椭圆 922x922，填充 #2D2C32，描边 #4D2D44；Turbulent Displace 数量 7 + Sound Keys，大小 150，演化 time*80 | 未知 | 未知 |
| L3 | 预合成 1 | 纯色 "白色 纯色 4"（控制层） | tc Sound Keys（读 旁白.mp3） | 未知 | 未知 |
| L4 | 预合成 1 | 纯色/空 | 只有材质选项 | 未知 | 未知 |
| L5 | 预合成 1 | **Element 3D 层 A**（"白色 纯色 3"，Element 控制被别层引用） | 见 (2) | 未知 | 未知 |
| L6 | 预合成 1 | **Element 3D 层 B** | 见 (2) | 未知 | 未知 |
| L7 | 预合成 1 | 带蒙版图层 | Mask 关键帧形状，Position 1127,744 | 未知 | 未知 |
| L8–L9 | 预合成 1/2 | 3D 空/灯组 | 见上"环境灯组" | — | — |
| L10 | 预合成 2 | 预合成层 | Time Remap 关键帧、Scale 关键帧 | 未知 | 未知 |
| L11 | 预合成 2 | 预合成层 + 蒙版 | Time Remap 关键帧、Mask、**Gaussian Blur 关键帧**、Vibrance 30、Scale 关键帧 | 未知 | 未知 |
| L12 | 预合成 2 | 纯色（背景） | Time Remap、**Fill ARGB(255,15,15,22)=#0F0F16**、Scale 关键帧 | 未知 | 未知 |
| L13 | 预合成 3 | 调整层？ | Exposure 关键帧 | 未知 | 未知 |
| L14 | 预合成 3 | 蒙版层（球壳） | Mask 关键帧，**Feather 97,97**，Position 1280,723，Scale/Opacity 关键帧 | 未知 | 关键帧 |
| L15 | 预合成 3 | 同 L14（第二份） | Feather 97,97，Position 1280,723 | 未知 | 关键帧 |
| L16 | 预合成 3 | 模糊层 | **Gaussian Blur 150**，Scale/Opacity 关键帧 | 未知 | 关键帧 |
| L17 | 预合成 3 | 蒙版层 | **Feather 210,210**，锚点 0.51,0.502，位置 1280,720，Scale/Opacity 关键帧 | 未知 | 关键帧 |
| L18 | 预合成 3 | 预合成层 | Time Remap、Scale 关键帧 | 未知 | 未知 |
| L19 | 预合成 3 | 调整层？ | Vibrance −20，Brightness&Contrast 亮度 −50，Exposure 关键帧 | 未知 | 未知 |
| L20 | 预合成 3 | 发光层 | **Color Key 红色 RGB(255,0,0) 容差 83**，Vibrance 100，**Gaussian Blur 200** | 未知 | 关键帧 |
| L21 | 预合成 3 | 发光层 | Simple Choker −2.3，**Gaussian Blur 30** | 未知 | 未知 |
| L22 | 预合成 3 | 调色层 | Vibrance −52，**Exposure 8.9** | 未知 | 关键帧 |
| L23 | 预合成 3 | 发光层 | Simple Choker −0.4，**Gaussian Blur 57** | 未知 | 未知 |
| L24 | 预合成 3 | 调色层 | Vibrance 60 | 未知 | 关键帧 |
| L25 | 预合成 3 | 发光层 | Simple Choker 38，**Gaussian Blur 111** | 未知 | 未知 |
| L26 | 预合成 3 | 调色层 | Vibrance 60 | 未知 | 关键帧 |
| L27 | 预合成 3 | 发光层 | Simple Choker −14.3，**Gaussian Blur 80** | 未知 | 未知 |
| L28 | 预合成 3 | 调色层 | Vibrance 60 | 未知 | 关键帧 |
| L29+ | 预合成 3 | 3D 灯组 | 同上 | — | — |
说明：L20–L28 呈"（收缩/扩张 + 高斯模糊 r）→（Vibrance +60，不透明度关键帧）"成对出现，**像是同一素材复制多层、每层不同模糊半径做的多级辉光**；这些层的混合模式（很可能是相加/屏幕）**未解出**，是重做时最大的空白。
Curves（ADBE CurvesCustom，通道"RGB|红色|绿色|蓝色|Alpha"）存在，点未解码，所在图层未定位。tree.txt 里还有图层样式块（outerGlow/innerGlow/bevelEmboss/innerShadow enabled 组），开关值未知。

## (2) Element 3D 层
两层（L5、L6），参数 ID 一致，结构是 5 个 Group（ID 段 01xx、05xx、29xx、33xx、37xx）+ 通道（10xxx/12xxx/13xxx）。
| 项 | 层 A（L5） | 层 B（L6） |
|---|---|---|
| 模型/基本形 | 未知（插件 blob） | 未知 |
| 挤出/倒角 | 未知 | 未知 |
| Twist/Bend 变形 | **未知**（文件里没有可识别的 Twist/Bend 参数名；可能在数字 ID 里，无对照表） | 未知 |
| 每组共同常量 | x18=10，x19/x20/x21=3,3,3，x05=10，x22=4，x12=0.5，x06=5，x02=0.5,0.5（层 A 部分组） | 同，x02 关键帧 |
| 组颜色参数？ | 0552/2952/3352/3752 = (255,255,255,255) 白（层 A）；层 B 为关键帧 | 关键帧 |
| 组旋转表达式 | 0192: `time*30+value` | 0192: `time*45+value`；0592: `-time*30+value`；2992: `-time*45+value`；3392: `time*30+value`；3792: `-time*45+value` |
| 其他 | 0363=0.9，0186=2，0185/0182–0184 关键帧，0152 关键帧 | 0363 关键帧；0582=−16；2983=14；3962=0.6 |
| 通道 wiggle | 10020=−7 `wiggle(0.2,15)`；10021=−21；10023 `wiggle(0.2,15)`；10024=−22 `wiggle(0.2,15)`；10006=2；10007 `wiggle(0.2,15)`；10008=−33 `wiggle(0.2,15)` | 10020=−19；10021 `wiggle(0.2,15)`；10023=4；10007=35 `wiggle(0.2,10)`；10008=65；10421 `wiggle(0.2,15)`；10408 `wiggle(0.2,30)`；12821/12824/13221/13207/13621/13624/13607/13608 `wiggle(0.2,15)`；13620=7 |
| Z 旋转中心/中心缩放 | — | 1708/1709 链接 `thisComp.layer("白色 纯色 3").effect("Element")("Z 旋转中心")/("中心缩放")` |
| 音频联动 | 表达式 `effect("Element")(2111)+thisComp.layer("白色 纯色 4").effect("Sound Keys")("输出 1")/…`（截断） | 同 |
| 渲染/全局（两层相同） | 0053=2，0055=2，0056=2，0061=50，0073/0077=0.5,0.5，0094–0097=50，1903/1905/1907=50，1702=0，1710=0.5,0.5，1533 关键帧，5128=2，**1274=(255,0,0,0…)**，1276=4，**1277=2.2**（疑似 gamma），1328=0.75，1329=10，1324=0.1，1205 关键帧，**1206=1040**，**1104=180**，**1154=1500**，**1155=64**，6113=2 | 同 |
| 材质（漫反射色/透明度/反射/Fresnel/自发光/辉光） | 未知（数字 ID 无法对应） | 未知 |
| 灯光 | AE 内只看到 6+3 个环境方向图层；E3D 内部灯：未知 | — |
| DOF/雾/运动模糊/多重采样 | 未知（1154=1500、1155=64、1206=1040 可能是雾/景深距离或采样数，**无法确认**）；aep_dump 与代码注释里的"fog start 2700, range 1000"在本次导出中**找不到出处** | — |
| 群组/粒子复制 | 5 个 Group/层，复制方式未知 | — |
注：aep_dump 说通道 CH1..CH10 有 旋转 X/Y/Z、位置、缩放、UV 偏移、不透明度。wiggle(0.2,15) = 0.2 Hz、±15 单位的慢速随机漂移。

## (3) 效果（按出现顺序，非默认值）
- 形状图层 1：Turbulent Displace 数量 7（+ `comp("预合成 1").layer("白色 纯色 4").effect("Sound Keys")("输出 1")+value`），大小 150，演化 `time*80`。
- 白色 纯色 4：tc Sound Keys 参数 0002=3，0005=0.1，0060=20，0061=80，0014=(0.248,0.432)，0015=(0.283,0.881)，0016=2，0017=0.5，0018=6，0020=10，0021 关键帧（音频输出）。
- 缩放表达式：`a=transform.scale[0]+comp("预合成 1").layer("白色 纯色 4").effect("Sound Keys")("输出 1"…`（截断）。
- L11：Gaussian Blur（关键帧）、Vibrance 30。
- L12：Fill #0F0F16。
- L13：Exposure（关键帧）。
- L16：Gaussian Blur 150。
- L19：Vibrance −20 → Brightness&Contrast −50 → Exposure（关键帧）。
- L20：Color Key 红 容差 83 → Vibrance 100 → Gaussian Blur 200。
- L21：Simple Choker −2.3 → Gaussian Blur 30。
- L22：Vibrance −52 → Exposure 8.9。
- L23：Simple Choker −0.4 → Gaussian Blur 57。
- L24/L26/L28：Vibrance 60。
- L25：Simple Choker 38 → Gaussian Blur 111。
- L27：Simple Choker −14.3 → Gaussian Blur 80。
- Curves：存在，点未知。Glow / Deep Glow / CC 系列 / Twist / Bend 效果：**导出里没有**。

## (4) 蒙版
| 图层 | 形状 | 羽化 |
|---|---|---|
| L1（合成 2） | 关键帧（未解码） | 1000,1000；Mask Offset 关键帧 |
| L7（预合成 1） | 关键帧（未解码） | 默认 |
| L11（预合成 2） | 关键帧 | 默认 |
| L14、L15（预合成 3） | 关键帧（球壳，aep_dump：椭圆 922x922） | 97,97 |
| L17（预合成 3） | 关键帧 | 210,210 |

## (6) Misaka 的笔记
未找到。只有评审 brief（`designer/round36/brief.txt`，第 36 轮目标：RGB 片各约 1/3、丝绸半透明正对 ~0.35 / 侧对亮边 ~1.0、很暗深蓝玻璃球 + 淡红/蓝色差外圈、小而锋利的多角白星）。需要 Grok Bot 提供笔记位置。

## (7) 现有 shader 怎么做的
文件：`/workspace/ComposeSiri/tools/siri-preview/src/main/kotlin/com/jetbrains/kmpapp/preview/SheetPrototype.kt`（846 行；`ComposeSiri-vol/` 下有同名副本），App 端 `shared/.../SiriOrb.kt`、`SiriSurfaceMesh.kt`。
- 片：`SheetSksl` 里 `sheetSdf(p, twist, bend, ws, uv)` —— SDF 薄板，沿长轴扭转 `a = twist*(u-0.5)`，透镜形截面 `y=v*W0*sqrt(1-u^2)`，弯曲 `z=b*(u^2-0.3)`；8 片（k0..k7），每片 pitch/azimuth/roll 手写正弦，k3/k5/k6 是"刃"。idle twist `mix(1.4,1.75)+wig*0.5`，bend 0.06~0.25。**对应 E3D 模型+变形，但 AE 的真实形状/Twist 值未知，现在全凭目测。**
- 运动：正弦摆动近似 `wiggle(0.2,…)`，6s 无缝循环；AE 是 `time*30/45` 度/秒 匀速组旋转 + 0.2Hz 随机 wiggle（非周期）。
- 透明度/材质：主体 `a=0.9*…*fog`，丝绸 `aSilk = aOrient*aInward*aEdge*fog`，面内 faceShade 0.4→边缘 1.0；无真实反射/Fresnel（另有 `fresnelFx` 后处理）。AE 是 Glossiness 100、IOR 1、六方向环境反射，E3D 材质未知。
- 混合：shader 内按深度 OVER；身体图层 SRC_OVER，刃层 PLUS，中心光/辉光 PLUS，白色溢光 SCREEN。AE 每层混合模式未知。
- 深度雾：shader 内 `fog = pow(clamp(0.4+0.6*z/0.8),1.6)`，idle 地板 0.6；后层单独 pass 模糊 ~3px（front/back 两个 pass）；`fogFx` 球内蓝紫雾 0.06→0.12。
- 刃：`uBlade` pass，屏幕空间中心线 `bladeAcc`，尾部 30% 淡出，再 24px(@870) 高斯 PLUS + 青色矩阵。
- 辉光：中心 HDR 核（多次 PLUS 模糊圆）+ `glowFx` 径向 0.25R；HDR 阈值 bloom 200/80/30 半径在 idle 关闭；对应 AE 的 30/57/80/111/150/200 多级模糊辉光栈，**现在 idle 没有这套多级辉光**。
- 调色：`tonemap` ACES + 饱和 x1.3；AE 是 Vibrance +60 x多层、+100、−20、−52，Exposure 8.9，B&C −50，Curves。
- 扰乱置换：现有代码没有（AE 在形状层上 7/150/time*80）。
- 球壳：tonemap 后画圆（idle 纯黑）+ `bodyImg` 模糊 0.25R 取色、DST_IN 留 0.8~1.0R 光带；AE 是 922x922 椭圆 #2D2C32、描边 #4D2D44、羽化 97，背景 #0F0F16。
