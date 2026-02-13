package com.hinnka.mycamera.raw

/**
 * RAW 图像处理的 GLSL 着色器
 * 
 * 实现完整的 RAW 处理管线：
 * 1. 黑电平校正和归一化
 * 2. Malvar-He-Cutler (MHC) 解马赛克算法
 * 3. 白平衡增益
 * 4. 色彩校正矩阵 (CCM)
 * 5. Gamma 校正 (sRGB)
 */
object RawShaders {

    /**
     * 顶点着色器 - 简单的全屏四边形渲染
     */
    val VERTEX_SHADER = """
        #version 300 es
        
        in vec4 aPosition;
        in vec2 aTexCoord;
        
        out vec2 vTexCoord;
        
        uniform mat4 uTexMatrix;
        
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    /**
     * 片元着色器 - Linear RGB 处理管线 (用于 Stacked RAW)
     * 跳过解马赛克，但保留 CCM/Gamma/ToneMapping/Sharpening
     */
    val FRAGMENT_SHADER_LINEAR = """
        #version 300 es

        precision highp float;
        precision highp int;
        precision highp usampler2D;

        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform usampler2D uRawTexture; // RGB16UI
        uniform vec2 uImageSize;
        uniform mat3 uColorCorrectionMatrix;
        uniform sampler2D uLensShadingMap;
        
        uniform float uExposureGain;       // 曝光增益


        void main() {
            ivec2 coord = ivec2(gl_FragCoord.xy);
            
            // 直接读取 Linear RGB (16-bit Normalized to 0..1)
            // Stack output is 0..65535
            uvec3 raw = texelFetch(uRawTexture, coord, 0).rgb;
            vec3 rgb = vec3(raw) / 65535.0;

            // 1. CCM
            rgb = uColorCorrectionMatrix * rgb;

            // Output Linear (由下一步 ToneMap Pass 处理)
            fragColor = vec4(rgb, 1.0);
        }
    """.trimIndent()

    /**
     * 全屏四边形顶点坐标
     */
    val FULL_QUAD_VERTICES = floatArrayOf(
        -1.0f, -1.0f,  // 左下
        1.0f, -1.0f,  // 右下
        -1.0f, 1.0f,  // 左上
        1.0f, 1.0f   // 右上
    )

    /**
     * 纹理坐标（Y 轴翻转，适配 Android Bitmap）
     */
    val TEXTURE_COORDS = floatArrayOf(
        0.0f, 0.0f,  // LB viewport -> Tex (0,0) [Sensor Row 0/Bottom of Tex] -> glReadPixels reads to Bitmap Top
        1.0f, 0.0f,  // RB viewport -> Tex (1,0)
        0.0f, 1.0f,  // LT viewport -> Tex (0,1)
        1.0f, 1.0f   // RT viewport -> Tex (1,1)
    )

    /**
     * 片元着色器 - 第二步：将解马赛克后的 RGB 纹理渲染到最终尺寸
     * 应用旋转、裁切和缩放
     */
    val PASSTHROUGH_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        
        in vec2 vTexCoord;
        out vec4 fragColor;
        
        uniform sampler2D uTexture;
        
        void main() {
            fragColor = texture(uTexture, vTexCoord);
        }
    """.trimIndent()

    /**
     * Tone Mapping Shader
     */
    val TONEMAP_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        
        in vec2 vTexCoord;
        out vec4 fragColor;
        
        uniform sampler2D uInputTexture;
        uniform vec4 uToneMapParams; // (ExposureGain, DRC_Strength, BlackPoint, WhitePoint)
        uniform vec2 uTexelSize;
        
        
        vec3 linearToSRGB(vec3 linear) {
            return mix(
                12.92 * linear,
                1.055 * pow(max(linear, 0.0), vec3(1.0/2.4)) - 0.055,
                step(0.0031308, linear)
            );
        }
        
        float getLumaLinear(vec3 c) {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }
        
        vec3 applyHighlightRecovery(vec3 color, float w) {
            float luma = getLumaLinear(color);
            float numerator = luma * (1.0 + (luma / (w * w)));
            float compressed = numerator / (1.0 + luma);
            return color * (compressed / luma);
        }
        
        void main() {
            vec3 rawColor = texture(uInputTexture, vTexCoord).rgb;
            
            float gain = uToneMapParams.x;
            float drcStrength = uToneMapParams.y;
            float blackPoint = uToneMapParams.z;
            float whitePoint = uToneMapParams.w;
            
            // 1. 扣除黑点
            vec3 color = max(vec3(0.0), rawColor - blackPoint);
            
            // 2. 应用曝光增益
            color *= gain;
            
            float maxWhite = max(1.0, whitePoint * gain);
            color = applyHighlightRecovery(color, maxWhite);
            
            // 6. 应用 sRGB Gamma 空间转换
            color = linearToSRGB(color);
            
            fragColor = vec4(color, 1.0);
        }
    """.trimIndent()


    /**
     * 绘制顺序索引
     */
    val DRAW_ORDER = shortArrayOf(
        0, 1, 2,  // 第一个三角形
        1, 3, 2   // 第二个三角形
    )


    /**
     * LUT Fragment Shader
     * 处理流程：Input Texture -> LUT -> Output
     */
    val LUT_FRAGMENT_SHADER = """
            #version 300 es

            precision highp float;

            in vec2 vTexCoord;
            out vec4 fragColor;

            uniform sampler2D uInputTexture;
            uniform mediump sampler3D uLutTexture;
            uniform float uLutSize;
            uniform bool uLutEnabled;
            uniform int uLutCurve;
            uniform vec2 uTexelSize;

            // 后期处理参数
            uniform float uSharpening;           // 0.0 ~ 1.0 (锐化强度)
            
            float log10(float x) { return log(x) * 0.4342944819; }
            vec3 log10(vec3 x) { return log(x) * 0.4342944819; }
            
            vec3 srgbToLinear(vec3 c) {
                return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
            }

            vec3 applyLutCurve(vec3 rgb, int curveType) {
                if (curveType == 0) return rgb; // SRGB
                vec3 l = srgbToLinear(rgb);
                if (curveType == 1) return l; // LINEAR
                if (curveType == 2) { // V-Log
                    return mix(5.6 * l + 0.125, 0.241514 * log10(l + 0.00873) + 0.598206, step(0.01, l));
                }
                if (curveType == 3) { // S-Log3
                    return mix((l * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0, (420.0 + log10((l + 0.01) / (0.18 + 0.01)) * 261.5) / 1023.0, step(0.01125, l));
                }
                if (curveType == 4) { // F-Log2
                    return mix(8.799461 * l + 0.092864, 0.245281 * log10(5.555556 * l + 0.064829) + 0.384316, step(0.00089, l));
                }
                if (curveType == 5) { // LogC
                    return mix(5.367655 * l + 0.092809, 0.247190 * log10(5.555556 * l + 0.052272) + 0.385537, step(0.010591, l));
                }
                if (curveType == 6) { // AppleLog
                    return mix(mix(vec3(0.0), 47.28711236 * pow(l + 0.05641088, vec3(2.0)), step(-0.05641088, l)), 0.08550479 * (log(l + 0.00964052) / log(2.0)) + 0.69336945, step(0.01, l));
                }
                if (curveType == 7) { // HLG
                    float ha = 0.17883277;
                    float hb = 1.0 - 4.0 * ha;
                    float hc = 0.5 - ha * log(4.0 * ha);
                    return mix(sqrt(3.0 * l), ha * log(12.0 * l - hb) + hc, step(1.0 / 12.0, l));
                }
                return rgb;
            }

            void main() {
                vec4 color = texture(uInputTexture, vTexCoord);

                if (uLutEnabled) {
                    vec3 lutInColor = applyLutCurve(color.rgb, uLutCurve);
                    float scale = (uLutSize - 1.0) / uLutSize;
                    float offset = 1.0 / (2.0 * uLutSize);
                    vec3 lutCoord = lutInColor * scale + offset;
                   color = texture(uLutTexture, lutCoord);
                }

                // === 后期处理：锐化（在 LUT 之后，作为最后步骤） ===
                if (uSharpening > 0.0) {
                    vec3 inputColor = texture(uInputTexture, vTexCoord).rgb;
                    float inputLuma = dot(inputColor, vec3(0.299, 0.587, 0.114));
                    float neighborsLuma = 0.0;
                    neighborsLuma += dot(texture(uInputTexture, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                    neighborsLuma += dot(texture(uInputTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                    neighborsLuma += dot(texture(uInputTexture, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                    neighborsLuma += dot(texture(uInputTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                    float blurLuma = neighborsLuma * 0.25;
                    float detail = inputLuma - blurLuma;
                    float sharpenAmount = uSharpening * 1.5;
                    color.rgb += detail * sharpenAmount;
                    color.rgb = clamp(color.rgb, 0.0, 1.0);
                }
                fragColor = color;
            }
        """.trimIndent()
}
