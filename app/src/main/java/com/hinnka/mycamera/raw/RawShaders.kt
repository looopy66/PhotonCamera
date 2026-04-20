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
        uniform vec3 uBlackLevel; // Sensor black level or encoded-domain black point
        uniform vec3 uWhiteLevel; // Sensor white level or encoded-domain full scale


        void main() {
            ivec2 coord = ivec2(gl_FragCoord.xy);
            
            // 直接读取 Linear RGB (16-bit Normalized to 0..1)
            // Stack output is 0..65535
            uvec3 raw = texelFetch(uRawTexture, coord, 0).rgb;
            vec3 sensor = vec3(raw);
            vec3 safeWhiteLevel = max(uWhiteLevel, uBlackLevel + vec3(1.0));
            vec3 rgb = clamp((sensor - uBlackLevel) / (safeWhiteLevel - uBlackLevel), 0.0, 1.0);

            // 1. CCM
            rgb = uColorCorrectionMatrix * rgb;
            
            rgb = rgb * uExposureGain;

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
     * Combined Processing Shader
     *
     * Process chain:
     * 1. Linear RGB input in working space
     * 2. Local SDR tone mapping
     * 3. ACR3 curve
     * 4. Working space -> Linear sRGB
     * 5. Linear sRGB -> sRGB
     */
    val COMBINED_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        
        in vec2 vTexCoord;
        out vec4 fragColor;
        
        uniform sampler2D uInputTexture;
        uniform sampler2D uCurveTexture;
        uniform mat3 uOutputTransform;
        uniform float uCurveSize;
        uniform bool uCurveEnabled;

        float sampleCurve(float value) {
            if (!uCurveEnabled || uCurveSize <= 1.0) {
                return value;
            }
            float clampedValue = clamp(value, 0.0, 1.0);
            float coordX = clampedValue * ((uCurveSize - 1.0) / uCurveSize) + (0.5 / uCurveSize);
            return texture(uCurveTexture, vec2(coordX, 0.5)).r;
        }

        vec3 applyCurve(vec3 color) {
            return vec3(
                sampleCurve(color.r),
                sampleCurve(color.g),
                sampleCurve(color.b)
            );
        }

        vec3 linearToSrgb(vec3 color) {
            vec3 clampedColor = max(color, vec3(0.0));
            vec3 low = clampedColor * 12.92;
            vec3 high = 1.055 * pow(clampedColor, vec3(1.0 / 2.4)) - 0.055;
            bvec3 useHigh = greaterThan(clampedColor, vec3(0.0031308));
            return vec3(
                useHigh.r ? high.r : low.r,
                useHigh.g ? high.g : low.g,
                useHigh.b ? high.b : low.b
            );
        }
        
        float luminance(vec3 color) {
            return max(dot(color, vec3(0.2126, 0.7152, 0.0722)), 1e-4);
        }

        float sampleLuma(vec2 uv) {
            return luminance(texture(uInputTexture, uv).rgb);
        }

        float localAverageLuma(vec2 uv, vec2 texelSize, float radius) {
            vec2 dx = vec2(texelSize.x * radius, 0.0);
            vec2 dy = vec2(0.0, texelSize.y * radius);

            float center = sampleLuma(uv) * 0.28;
            float axial =
                sampleLuma(clamp(uv + dx, vec2(0.0), vec2(1.0))) +
                sampleLuma(clamp(uv - dx, vec2(0.0), vec2(1.0))) +
                sampleLuma(clamp(uv + dy, vec2(0.0), vec2(1.0))) +
                sampleLuma(clamp(uv - dy, vec2(0.0), vec2(1.0)));
            float diagonal =
                sampleLuma(clamp(uv + dx + dy, vec2(0.0), vec2(1.0))) +
                sampleLuma(clamp(uv + dx - dy, vec2(0.0), vec2(1.0))) +
                sampleLuma(clamp(uv - dx + dy, vec2(0.0), vec2(1.0))) +
                sampleLuma(clamp(uv - dx - dy, vec2(0.0), vec2(1.0)));

            return center + axial * 0.12 + diagonal * 0.06;
        }

        vec3 reinhardLocalTonemapping(vec3 sceneLinear) {
            vec2 texelSize = 1.0 / vec2(textureSize(uInputTexture, 0));
            float sceneLuma = luminance(sceneLinear);

            float localSmall = localAverageLuma(vTexCoord, texelSize, 1.5);
            float localLarge = localAverageLuma(vTexCoord, texelSize, 4.0);
            float scaleContrast = abs(log2(localSmall + 1e-4) - log2(localLarge + 1e-4));
            float localAdaptation = mix(localLarge, localSmall, smoothstep(0.08, 0.35, scaleContrast));

            float adaptedLuma = sceneLuma / (1.0 + localAdaptation);
            float mappedLuma = adaptedLuma / (1.0 + adaptedLuma);
            float chromaScale = mappedLuma / sceneLuma;
            return clamp(sceneLinear * chromaScale, 0.0, 1.0);
        }
        
        void main() {
            vec3 color = texture(uInputTexture, vTexCoord).rgb;

            color = reinhardLocalTonemapping(color);
            color = applyCurve(color);
            color = uOutputTransform * color;
            color = linearToSrgb(color);

            fragColor = vec4(color, 1.0);
        }
    """.trimIndent()


    /**
     * Dedicated Sharpening Shader
     * Using a Laplacian-style mask for detail enhancement
     */
    val SHARPEN_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        
        in vec2 vTexCoord;
        out vec4 fragColor;
        
        uniform sampler2D uInputTexture;
        uniform vec2 uTexelSize;
        uniform float uSharpening;
        
        void main() {
            vec3 center = texture(uInputTexture, vTexCoord).rgb;
            if (uSharpening <= 0.0) {
                fragColor = vec4(center, 1.0);
                return;
            }
            
            // Simple Laplacian Sharpening
            vec3 left   = texture(uInputTexture, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
            vec3 right  = texture(uInputTexture, vTexCoord + vec2( uTexelSize.x, 0.0)).rgb;
            vec3 top    = texture(uInputTexture, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
            vec3 bottom = texture(uInputTexture, vTexCoord + vec2(0.0,  uTexelSize.y)).rgb;
            
            vec3 edge = 4.0 * center - left - right - top - bottom;
            vec3 result = center + edge * (uSharpening * 0.5);
            
            fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    /**
     * HDR Reference Shader
     *
     * 对线性输入仅应用共享曝光归一化，不做 SDR tone mapping，
     * 供后续 gainmap 计算使用。
     */
    val HDR_REFERENCE_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;

        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform sampler2D uInputTexture;

        void main() {
            vec3 color = texture(uInputTexture, vTexCoord).rgb;
            fragColor = vec4(max(color, vec3(0.0)), 1.0);
        }
    """.trimIndent()

    /**
     * 绘制顺序索引
     */
    val DRAW_ORDER = shortArrayOf(
        0, 1, 2,  // 第一个三角形
        1, 3, 2   // 第二个三角形
    )
}
