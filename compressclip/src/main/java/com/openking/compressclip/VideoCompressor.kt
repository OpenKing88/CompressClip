package com.openking.compressclip

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.openking.compressclip.compressor.Compressor.compressVideo
import com.openking.compressclip.compressor.Compressor.isRunning
import com.openking.compressclip.config.*
import com.openking.compressclip.video.Result
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream


enum class VideoQuality {
    VERY_HIGH, HIGH, MEDIUM, LOW, VERY_LOW
}

object VideoCompressor : CoroutineScope by MainScope() {

    private var job: Job? = null

    @JvmStatic
    fun start(
        context: Context,
        uris: List<Uri>,
        isStreamable: Boolean = false,
        storageConfiguration: StorageConfiguration,
        configureWith: Configuration,
        listener: CompressionListener,
    ) {

        if (configureWith.videoNames.isNullOrEmpty()) {
            configureWith.videoNames = (uris.map { uri -> uri.pathSegments.last() })
        }
        // Only one is allowed
        assert(configureWith.videoNames!!.size == uris.size)

        doVideoCompression(
            context,
            uris,
            isStreamable,
            storageConfiguration,
            configureWith,
            listener,
        )
    }

    /**
     * Call this function to cancel video compression process which will call [CompressionListener.onCancelled]
     */
    @JvmStatic
    fun cancel() {
        job?.cancel()
        isRunning = false
    }

    private fun doVideoCompression(
        context: Context,
        uris: List<Uri>,
        isStreamable: Boolean,
        storageConfiguration: StorageConfiguration,
        configuration: Configuration,
        listener: CompressionListener,
    ) {
        var streamableFile: File? = null
        for (i in uris.indices) {

            val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
                listener.onFailure(i, throwable.message ?: "")
            }
            val coroutineScope = CoroutineScope(Job() + coroutineExceptionHandler)

            job = coroutineScope.launch(Dispatchers.IO) {

                val job = async { getMediaPath(context, uris[i]) }
                val path = job.await()

                val desFile = saveVideoFile(
                    context,
                    path,
                    storageConfiguration,
                    isStreamable,
                    configuration.videoNames!![i],
                    shouldSave = false
                )

                if (isStreamable)
                    streamableFile = saveVideoFile(
                        context,
                        path,
                        storageConfiguration,
                        null,
                        configuration.videoNames!![i],
                        shouldSave = false
                    )

                desFile?.let {
                    isRunning = true
                    listener.onStart(i)
                    val result = startCompression(
                        i,
                        context,
                        uris[i],
                        desFile.path,
                        streamableFile?.path,
                        configuration,
                        listener,
                    )

                    // Runs in Main(UI) Thread
                    if (result.success) {
                        val savedFile = saveVideoFile(
                            context,
                            result.path,
                            storageConfiguration,
                            isStreamable,
                            configuration.videoNames!![i],
                            shouldSave = true
                        )

                        listener.onSuccess(i, result.size, savedFile?.path)
                    } else {
                        listener.onFailure(i, result.failureMessage ?: "An error has occurred!")
                    }
                }
            }
        }
    }

    private suspend fun startCompression(
        index: Int,
        context: Context,
        srcUri: Uri,
        destPath: String,
        streamableFile: String? = null,
        configuration: Configuration,
        listener: CompressionListener,
    ): Result = withContext(Dispatchers.Default) {
        return@withContext compressVideo(
            index,
            context,
            srcUri,
            destPath,
            streamableFile,
            configuration,
            object : CompressionProgressListener {
                override fun onProgressChanged(index: Int, percent: Float) {
                    listener.onProgress(index, percent)
                }

                override fun onProgressCancelled(index: Int) {
                    listener.onCancelled(index)
                }
            }
        )
    }

    private fun saveVideoFile(
        context: Context,
        filePath: String?,
        storageConfiguration: StorageConfiguration,
        isStreamable: Boolean?,
        videoName: String,
        shouldSave: Boolean
    ): File? {
        return filePath?.let {
            val videoFile = File(filePath)
            storageConfiguration.createFileToSave(
                context,
                videoFile,
                validatedFileName(
                    videoName,
                    isStreamable
                ),
                shouldSave
            )
        }
    }

    private fun getMediaPath(context: Context, uri: Uri): String {

        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, projection, null, null, null)
            return if (cursor != null) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                cursor.moveToFirst()
                cursor.getString(columnIndex)

            } else throw Exception()

        } catch (e: Exception) {
            resolver.let {
                val filePath = (context.applicationInfo.dataDir + File.separator
                        + System.currentTimeMillis())
                val file = File(filePath)

                resolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        val buf = ByteArray(4096)
                        var len: Int
                        while (inputStream.read(buf).also { len = it } > 0) outputStream.write(
                            buf,
                            0,
                            len
                        )
                    }
                }
                return file.absolutePath
            }
        } finally {
            cursor?.close()
        }
    }

    private fun validatedFileName(name: String, isStreamable: Boolean? = null): String {
        val videoName = if (isStreamable == null || !isStreamable) name
        else "${name}_temp"

        if (!videoName.contains("mp4")) return "${videoName}.mp4"
        return videoName
    }
}
