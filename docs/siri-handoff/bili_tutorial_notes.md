# B站教程流程笔记（watchVideo 拆解，原样保留，未核实的数值标"未显示"）
关键发现：
- 片 = E3D 挤出的椭圆（薄圆盘，Bevel 0，Extreme 精度），不是透镜/飘带参数曲面。
- 片的贴图 = 白色固态层 + 反相圆形蒙版，羽化 1000 → 中心透明、边缘亮的径向渐变（亮边暗心来自贴图，不是光照）。
- 材质：贴图不透明度 78%，混合模式 Screen；Glossiness 0；光照影响 0（不受灯光）；Exposure 2。
- 形变：Twist + Bend（数值未显示），表达式 wiggle(0.2,10) / wiggle(0.2,15)。
- 旋转：Group1 Random Rotation = time*45+value；Group2 = -time*30+value（反向），共 6 片（5+1 两个 E3D 层）。
- 雾：Fog 100%，Start ~2700，Range 1000（深度雾，后面的片变暗）。
- 中心光：Optical Flares 缩放 800 + 模糊 450；白色固态椭圆羽化 210，Add，复制两层。
- 球边：深灰椭圆 + Turbulent Displace(7,150)；形状预合成放大盖过球边 + Curves + 反相椭圆挖空 + 高斯 250 + Vibrance，用椭圆做 Alpha 遮罩；底色 Fill 深蓝；球边 4 层：Choker -15/Blur 80（反相遮罩），Choker 38/Blur 110，Choker -0.4/Blur 57，Vibrance -52/Exposure 8.9/Choker -2.3/Blur 30。
- 环境底：Extract 黑点 83 + Vibrance 100 + 高斯 200（只在说话时出现）。
- 最后：调整层 Vibrance + 亮度对比度 + Exposure，再一层 Vibrance。
完整逐时间点记录见对话中的 watchVideo 结果。
