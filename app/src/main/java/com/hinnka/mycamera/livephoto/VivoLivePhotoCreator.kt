package com.hinnka.mycamera.livephoto

import android.content.Context
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Live Photo creator for Vivo devices.
 * Vivo Live Photos consist of two separate files: Image.jpg and Image.mp4.
 * Both files have a specific JSON metadata ("vivo{...}") and magic bytes appended at the end.
 */
class VivoLivePhotoCreator(private val context: Context) : LivePhotoCreator {
    private val TAG = "VivoLivePhotoCreator"
    
    // Magic numbers found in Vivo live photos (vcameralbum!)
    private val magic1 = intArrayOf(0, 0, 1, 118, 99, 97, 109, 101, 114, 97, 108, 98, 117, 109, 33, 0, 0, 0, 47)
    // Extra padding/marker bytes
    private val magic2 = intArrayOf(255, 255, 255, 255, 27, 42, 57, 72, 87, 102, 117, 132, 147, 162, 179)
    // Platform ID / Secret key?
    private val magic3 = byteArrayOf(54, 51, 53, 56, 53, 53, 53, 48, 48, 48, 48, 48, 48, 48, 48)

    override fun create(
        jpegPath: String,
        videoPath: String,
        outputPath: String,
        presentationTimestampUs: Long
    ): Boolean {
        val timestamp = System.currentTimeMillis().toString()
        
        // For Vivo, we need two separate output files: output.jpg and output.mp4
        val videoOutputPath = if (outputPath.lowercase().endsWith(".jpg")) {
            outputPath.substring(0, outputPath.length - 4) + ".mp4"
        } else if (outputPath.lowercase().endsWith(".jpeg")) {
            outputPath.substring(0, outputPath.length - 5) + ".mp4"
        } else {
            "$outputPath.mp4"
        }

        try {
            // Write JPEG with Vivo metadata
            writeVivoFile(jpegPath, File(outputPath), timestamp)
            // Write Video with Vivo metadata
            writeVivoFile(videoPath, File(videoOutputPath), timestamp)
            
            PLog.d(TAG, "Vivo Live Photo created: $outputPath and $videoOutputPath")
            return true
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to create Vivo Live Photo", e)
            return false
        }
    }

    private fun writeVivoFile(inputPath: String, outputFile: File, timestamp: String) {
        FileInputStream(inputPath).use { fis ->
            FileOutputStream(outputFile).use { fos ->
                // 1. Copy original content
                fis.copyTo(fos)

                // 2. Append Vivo JSON metadata
                // This ID (timestamp + magic3) links the JPEG and MP4
                val vivoJson = String.format(
                    "vivo{\"com.android.camera.joint.fullview.orientation\":0,\"com.android.camera.fisheye\":-1,\"com.android.camera.takenmodel\":\"%s\",\"com.android.camera.watermarkVersion\":null,\"com.android.camera.camerafacing\":\"0\",\"com.android.camera.moduleid\":\"live_photo\",\"com.android.camera.livephoto\":\"%s%s\",\"version\":2014,\"com.android.camera.joint.fullview\":false}",
                    "vivo X100 Pro", timestamp, String(magic3)
                )
                fos.write(vivoJson.toByteArray(StandardCharsets.US_ASCII))

                // 3. Append Magic1
                for (b in magic1) {
                    fos.write(b)
                }

                // 4. Append Timestamp
                for (c in timestamp) {
                    fos.write(c.code)
                }

                // 5. Append Magic3
                fos.write(magic3)

                // 6. Append Magic2
                for (b in magic2) {
                    fos.write(b)
                }
            }
        }
    }
}
