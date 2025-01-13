package com.openking.compressclip.config

import android.content.Context
import android.os.Build
import android.os.Environment
import com.openking.compressclip.VideoQuality
import com.openking.compressclip.utils.saveVideoInExternal
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class Configuration(
    var quality: VideoQuality = VideoQuality.VERY_HIGH,
    var isMinBitrateCheckEnabled: Boolean = true,
    var videoBitrateInMbps: Int? = null,
    var disableAudio: Boolean = false,
    val resizer: VideoResizer? = VideoResizer.auto,
    var startTime: Int? = null,
    var endTime: Int? = null,
    var maxSize: Int? = null,//压缩后视频最大值 单位MB 如果计算压缩后视频大小超过该值那么会自动降低quality来适应maxSize
    var videoNames: List<String>? = null
)

private fun getVideoResizer(
    keepOriginalResolution: Boolean,
    videoHeight: Double?,
    videoWidth: Double?
): VideoResizer? =
    if (keepOriginalResolution) {
        null
    } else if (videoWidth != null && videoHeight != null) {
        VideoResizer.matchSize(videoWidth, videoHeight, true)
    } else {
        VideoResizer.auto
    }

interface StorageConfiguration {
    fun createFileToSave(
        context: Context,
        videoFile: File,
        fileName: String,
        shouldSave: Boolean
    ): File
}

class AppSpecificStorageConfiguration(
    private val subFolderName: String? = null,
) : StorageConfiguration {

    override fun createFileToSave(
        context: Context,
        videoFile: File,
        fileName: String,
        shouldSave: Boolean
    ): File {
        val fullPath =
            if (subFolderName != null) "${subFolderName}/$fileName"
            else fileName

        if (!File("${context.filesDir}/$fullPath").exists()) {
            File("${context.filesDir}/$fullPath").parentFile?.mkdirs()
        }
        return File(context.filesDir, fullPath)
    }
}


enum class SaveLocation {
    pictures,
    downloads,
    movies,
}

class SharedStorageConfiguration(
    private val saveAt: SaveLocation? = null,
    private val subFolderName: String? = null,
) : StorageConfiguration {

    override fun createFileToSave(
        context: Context,
        videoFile: File,
        fileName: String,
        shouldSave: Boolean
    ): File {
        val saveLocation =
            when (saveAt) {
                SaveLocation.downloads -> {
                    Environment.DIRECTORY_DOWNLOADS
                }

                SaveLocation.pictures -> {
                    Environment.DIRECTORY_PICTURES
                }

                else -> {
                    Environment.DIRECTORY_MOVIES
                }
            }

        if (Build.VERSION.SDK_INT >= 29) {
            val fullPath =
                if (subFolderName != null) "$saveLocation/${subFolderName}"
                else saveLocation
            if (shouldSave) {
                saveVideoInExternal(context, fileName, fullPath, videoFile)
                File(context.filesDir, fileName).delete()
                return File("/storage/emulated/0/${fullPath}", fileName)
            }
            return File(context.filesDir, fileName)
        } else {
            val savePath =
                Environment.getExternalStoragePublicDirectory(saveLocation)

            val fullPath =
                if (subFolderName != null) "$savePath/${subFolderName}"
                else savePath.path

            val desFile = File(fullPath, fileName)

            if (!desFile.exists()) {
                try {
                    desFile.parentFile?.mkdirs()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            if (shouldSave) {
                context.openFileOutput(fileName, Context.MODE_PRIVATE)
                    .use { outputStream ->
                        FileInputStream(videoFile).use { inputStream ->
                            val buf = ByteArray(4096)
                            while (true) {
                                val sz = inputStream.read(buf)
                                if (sz <= 0) break
                                outputStream.write(buf, 0, sz)
                            }

                        }
                    }

            }
            return desFile
        }
    }
}

class CacheStorageConfiguration(
) : StorageConfiguration {
    override fun createFileToSave(
        context: Context,
        videoFile: File,
        fileName: String,
        shouldSave: Boolean
    ): File =
        File.createTempFile(videoFile.nameWithoutExtension, videoFile.extension)
}
