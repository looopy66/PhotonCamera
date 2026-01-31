package com.hinnka.mycamera.livephoto

import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Motion Photo 文件合成器
 *
 * 将 JPEG 静态图片和 MP4 视频合成为符合 Android Motion Photo 1.0 规范的文件。
 *
 * 文件结构:
 * ```
 * +-------------------+
 * |  JPEG 主图像      |  (带 XMP 元数据)
 * +-------------------+
 * |  MP4 视频         |  (紧随其后)
 * +-------------------+
 * ```
 *
 * 规范参考: https://developer.android.com/media/platform/motion-photo-format
 */
object MotionPhotoWriter {
    private const val TAG = "MotionPhotoWriter"

    private const val JPEG_APP1 = 0xFFE1    // APP1 (EXIF/XMP)
    private const val JPEG_SOS = 0xFFDA     // Start of Scan

    /**
     * 合成 Motion Photo 文件
     *
     * @param jpegPath 静态 JPEG 图片路径
     * @param videoPath MP4 视频路径
     * @param outputPath 输出 Motion Photo 路径
     * @return 是否成功
     */
    fun write(
        jpegPath: String,
        videoPath: String,
        outputPath: String,
        presentationTimestampUs: Long = 0
    ): Boolean {
        try {
            val jpegFile = File(jpegPath)
            val videoFile = File(videoPath)

            if (!jpegFile.exists()) {
                PLog.e(TAG, "JPEG file not found: $jpegPath")
                return false
            }
            if (!videoFile.exists()) {
                PLog.e(TAG, "Video file not found: $videoPath")
                return false
            }

            val videoLength = videoFile.length()

            // 生成 Motion Photo XMP 元数据
            val xmpData = buildMotionPhotoXmp(videoLength, presentationTimestampUs)

            // 使用流式处理，避免大文件 OOM
            FileOutputStream(outputPath).use { output ->
                FileInputStream(jpegPath).use { jpegInput ->
                    // 1. 注入 XMP 并写入 JPEG 内容
                    if (!injectXmpToStream(jpegInput, output, xmpData)) {
                        PLog.e(TAG, "Failed to inject XMP")
                        return false
                    }
                }

                // 2. 追加 MP4 视频
                FileInputStream(videoPath).use { videoInput ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (videoInput.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            PLog.d(TAG, "Motion Photo created: $outputPath (Video: $videoLength, TS: $presentationTimestampUs)")
            return true

        } catch (e: Exception) {
            PLog.e(TAG, "Failed to write Motion Photo", e)
            return false
        }
    }

    /**
     * 将 XMP 元数据注入到 JPEG 流 (Robust Byte-level Scanner)
     *
     * 策略：根据 Motion Photo 1.0 规范，将 XMP 插入为第一个 APP1 段。
     * 即紧随 SOI (FF D8) 之后插入。
     */
    private fun injectXmpToStream(input: FileInputStream, output: FileOutputStream, xmpData: ByteArray): Boolean {
        try {
            val bis = java.io.BufferedInputStream(input)

            // 1. 验证 SOI (FF D8)
            val b1 = bis.read()
            val b2 = bis.read()
            if (b1 != 0xFF || b2 != 0xD8) {
                PLog.e(TAG, "Invalid JPEG: missing SOI marker")
                return false
            }
            output.write(b1)
            output.write(b2)

            // 2. 立即注入 XMP APP1 段
            val xmpNamespace = "http://ns.adobe.com/xap/1.0/\u0000"
            val namespaceBytes = xmpNamespace.toByteArray(StandardCharsets.UTF_8)
            val segmentLength = 2 + namespaceBytes.size + xmpData.size
            
            if (segmentLength > 65535) {
                PLog.e(TAG, "XMP data too large")
                return false
            }

            output.write(0xFF)
            output.write(0xE1)
            output.write((segmentLength shr 8) and 0xFF)
            output.write(segmentLength and 0xFF)
            output.write(namespaceBytes)
            output.write(xmpData)

            // 3. 复制剩余所有 JPEG 数据
            val buffer = ByteArray(8192)
            var len: Int
            while (bis.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
            }
            return true
        } catch (e: Exception) {
            PLog.e(TAG, "Error injecting XMP", e)
            return false
        }
    }

    /**
     * 构建 Motion Photo XMP 元数据
     */
    private fun buildMotionPhotoXmp(videoLength: Long, presentationTimestampUs: Long): ByteArray {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description
                        xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                        xmlns:OpCamera="http://ns.oppo.com/photo/1.0/camera/"
                        Camera:MotionPhoto="1"
                        Camera:MotionPhotoVersion="1"
                        Camera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs"
                        GCamera:MotionPhoto="1"
                        GCamera:MotionPhotoVersion="1"
                        GCamera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs"
                        GCamera:MicroVideo="1"
                        GCamera:MicroVideoVersion="1"
                        GCamera:MicroVideoOffset="$videoLength"
                        OpCamera:MotionPhotoPrimaryPresentationTimestampUs="$presentationTimestampUs"
                        OpCamera:MotionPhotoOwner="PhotonCamera"
                        OpCamera:OLivePhotoVersion="1"
                        OpCamera:VideoLength="$videoLength"/>
                    <rdf:Description
                        xmlns:Container="http://ns.google.com/photos/1.0/container/"
                        xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                        <Container:Directory>
                            <rdf:Seq>
                                <rdf:li rdf:parseType="Resource">
                                    <Container:Item
                                        Item:Mime="image/jpeg"
                                        Item:Semantic="Primary"
                                        Item:Length="0"
                                        Item:Padding="0"/>
                                </rdf:li>
                                <rdf:li rdf:parseType="Resource">
                                    <Container:Item
                                        Item:Mime="video/mp4"
                                        Item:Semantic="MotionPhoto"
                                        Item:Length="$videoLength"/>
                                </rdf:li>
                            </rdf:Seq>
                        </Container:Directory>
                    </rdf:Description>
                </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        return xmp.toByteArray(StandardCharsets.UTF_8)
    }


    /**
     * 从 Motion Photo 文件的 XMP 中获取视频长度
     */
    private fun getVideoLength(motionPhotoPath: String): Long {
        try {
            val jpegData = File(motionPhotoPath).readBytes()
            var pos = 2
            while (pos < jpegData.size - 1) {
                val marker = ((jpegData[pos].toInt() and 0xFF) shl 8) or (jpegData[pos + 1].toInt() and 0xFF)
                if (marker == JPEG_SOS) break
                if (marker == JPEG_APP1) {
                    val length = ((jpegData[pos + 2].toInt() and 0xFF) shl 8) or (jpegData[pos + 3].toInt() and 0xFF)
                    val segmentData = jpegData.copyOfRange(pos + 4, minOf(pos + 4 + length - 2, jpegData.size))
                    val segmentString = String(segmentData, StandardCharsets.UTF_8)
                    if (segmentString.contains("MicroVideoOffset")) {
                        val regex = """MicroVideoOffset="(\d+)"""".toRegex()
                        val match = regex.find(segmentString)
                        if (match != null) return match.groupValues[1].toLongOrNull() ?: 0L
                    }
                    pos += 2 + length
                } else {
                    val length = ((jpegData[pos + 2].toInt() and 0xFF) shl 8) or (jpegData[pos + 3].toInt() and 0xFF)
                    pos += 2 + length
                }
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to parse XMP for offset", e)
        }
        return 0L
    }

    /**
     * 检查文件是否是 Motion Photo
     */
    fun isMotionPhoto(filePath: String): Boolean {
        try {
            val jpegData = File(filePath).readBytes()
            var pos = 2
            while (pos < jpegData.size - 1) {
                val marker = ((jpegData[pos].toInt() and 0xFF) shl 8) or (jpegData[pos + 1].toInt() and 0xFF)
                if (marker == JPEG_SOS) break
                if (marker == JPEG_APP1) {
                    val length = ((jpegData[pos + 2].toInt() and 0xFF) shl 8) or (jpegData[pos + 3].toInt() and 0xFF)
                    val segmentData = jpegData.copyOfRange(pos + 4, minOf(pos + 4 + length - 2, jpegData.size))
                    val segmentString = String(segmentData, StandardCharsets.UTF_8)
                    if (segmentString.contains("MicroVideo=\"1\"")) return true
                    pos += 2 + length
                } else {
                    val length = ((jpegData[pos + 2].toInt() and 0xFF) shl 8) or (jpegData[pos + 3].toInt() and 0xFF)
                    pos += 2 + length
                }
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to check Motion Photo", e)
        }
        return false
    }

    /**
     * 获取 Motion Photo 的视频时间戳
     */
    fun getPresentationTimestampUs(filePath: String): Long {
        try {
            val jpegData = File(filePath).readBytes()
            var pos = 2
            while (pos < jpegData.size - 1) {
                val marker = ((jpegData[pos].toInt() and 0xFF) shl 8) or (jpegData[pos + 1].toInt() and 0xFF)
                if (marker == JPEG_SOS) break
                if (marker == JPEG_APP1) {
                    val length = ((jpegData[pos + 2].toInt() and 0xFF) shl 8) or (jpegData[pos + 3].toInt() and 0xFF)
                    val segmentData = jpegData.copyOfRange(pos + 4, minOf(pos + 4 + length - 2, jpegData.size))
                    val segmentString = String(segmentData, StandardCharsets.UTF_8)
                    if (segmentString.contains("MicroVideoPresentationTimestampUs")) {
                        val regex = """MicroVideoPresentationTimestampUs="(\d+)"""".toRegex()
                        val match = regex.find(segmentString)
                        if (match != null) return match.groupValues[1].toLongOrNull() ?: -1L
                    }
                    pos += 2 + length
                } else {
                    val length = ((jpegData[pos + 2].toInt() and 0xFF) shl 8) or (jpegData[pos + 3].toInt() and 0xFF)
                    pos += 2 + length
                }
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to get timestamp", e)
        }
        return -1L
    }
}
