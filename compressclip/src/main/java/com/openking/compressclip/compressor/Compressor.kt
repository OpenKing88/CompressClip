package com.openking.compressclip.compressor

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.util.Log
import com.openking.compressclip.CompressionProgressListener
import com.openking.compressclip.config.Configuration
import com.openking.compressclip.utils.CompressorUtils.createEncoderOutputFileParameters
import com.openking.compressclip.utils.CompressorUtils.findTrack
import com.openking.compressclip.utils.CompressorUtils.getBitrate
import com.openking.compressclip.utils.CompressorUtils.prepareVideoHeight
import com.openking.compressclip.utils.CompressorUtils.prepareVideoWidth
import com.openking.compressclip.utils.CompressorUtils.printException
import com.openking.compressclip.utils.CompressorUtils.setUpMP4Movie
import com.openking.compressclip.utils.StreamableVideo
import com.openking.compressclip.utils.roundDimension
import com.openking.compressclip.video.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

object Compressor {

    // 2Mbps
    private const val MIN_BITRATE = 2000000

    // H.264 Advanced Video Coding
    const val MIME_TYPE = "video/avc"
    private const val MEDIACODEC_TIMEOUT_DEFAULT = 100L

    private const val INVALID_BITRATE =
        "提供的比特率小于压缩所需的比特率，请尝试将 isMinBitRateEnabled 设置为 false。"

    var isRunning = true

    suspend fun compressVideo(
        index: Int,
        context: Context,
        srcUri: Uri,
        destination: String,
        streamableFile: String?,
        configuration: Configuration,
        listener: CompressionProgressListener,
    ): Result = withContext(Dispatchers.Default) {

        val extractor = MediaExtractor()
        val mediaMetadataRetriever = MediaMetadataRetriever()
        try {
            mediaMetadataRetriever.setDataSource(context, srcUri)
        } catch (exception: Exception) {
            printException(exception)
            return@withContext Result(
                index,
                success = false,
                failureMessage = "${exception.message}"
            )
        }
        runCatching {
            extractor.setDataSource(context, srcUri, null)
        }
        val height: Double = prepareVideoHeight(mediaMetadataRetriever)
        val width: Double = prepareVideoWidth(mediaMetadataRetriever)
        val rotationData =
            mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val bitrateData =
            mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        val durationData =
            mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        if (rotationData.isNullOrEmpty() || bitrateData.isNullOrEmpty() || durationData.isNullOrEmpty()) {
            return@withContext Result(
                index,
                success = false,
                failureMessage = "提取视频元数据失败，请重试。"
            )
        }
        val startTime = (configuration.startTime ?: 0) * 1000 * 1000L
        val endTime = if (configuration.endTime != null) {
            configuration.endTime!! * 1000 * 1000L
        } else {
            durationData.toLong() * 1000
        }
        var (rotation, bitrate, duration) = try {
            //视频总时长
            val countDuration = durationData.toLong() * 1000
            //总计需要裁剪的时长
            val clipDuration = if (endTime > countDuration) {
                countDuration - startTime
            } else {
                endTime - startTime
            }
            val roundedSeconds = (clipDuration / 1000000.0).roundToInt()
            Triple(
                rotationData.toInt(),
                bitrateData.toInt(),
                roundedSeconds * 1000000L
            )
        } catch (e: java.lang.Exception) {
            return@withContext Result(
                index,
                success = false,
                failureMessage = "提取视频元数据失败，请重试。"
            )
        }

        // 压缩前检查最低视频比特率。
        // 注意：这是一个实验性值。
        if (configuration.isMinBitrateCheckEnabled && bitrate <= MIN_BITRATE)
            return@withContext Result(index, success = false, failureMessage = INVALID_BITRATE)

        //处理新的比特率值
        val newBitrate: Int =
            if (configuration.videoBitrateInMbps == null) getBitrate(bitrate, configuration.quality)
            else configuration.videoBitrateInMbps!! * 1000000

        //处理新的宽度和高度值
        val resizer = configuration.resizer
        val target = resizer?.resize(width, height) ?: Pair(width, height)
        var newWidth = roundDimension(target.first)
        var newHeight = roundDimension(target.second)

        //处理旋转值，并在需要时交换宽度和高度
        rotation = when (rotation) {
            90, 270 -> {
                val tempHeight = newHeight
                newHeight = newWidth
                newWidth = tempHeight
                0
            }

            180 -> 0
            else -> rotation
        }
        return@withContext start(
            index,
            newWidth,
            newHeight,
            destination,
            newBitrate,
            streamableFile,
            configuration.disableAudio,
            extractor,
            listener,
            duration,
            rotation,
            startTime,
            endTime
        )
    }

    @Suppress("DEPRECATION")
    private fun start(
        id: Int,
        newWidth: Int,
        newHeight: Int,
        destination: String,
        newBitrate: Int,
        streamableFile: String?,
        disableAudio: Boolean,
        extractor: MediaExtractor,
        compressionProgressListener: CompressionProgressListener,
        duration: Long,
        rotation: Int,
        startTime: Long,
        endTime: Long
    ): Result {

        if (newWidth != 0 && newHeight != 0) {
            val cacheFile = File(destination)
            try {
                // MediaCodec 访问编码器和解码器组件，并处理新的视频。
                //输入以生成压缩/更小尺寸的视频
                val bufferInfo = MediaCodec.BufferInfo()
                // 设置 mp4 movie
                val movie = setUpMP4Movie(rotation, cacheFile)
                // 在应用中，MediaMuxer 输出 MP4 文件。
                val mediaMuxer = MP4Builder().createMovie(movie)
                // 从视频轨道开始
                val videoIndex = findTrack(extractor, isVideo = true)
                extractor.selectTrack(videoIndex)
                //从startTime开始
                extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val inputFormat = extractor.getTrackFormat(videoIndex)
                val outputFormat: MediaFormat =
                    MediaFormat.createVideoFormat(MIME_TYPE, newWidth, newHeight)
                val decoder: MediaCodec
                //创建编码器encoder并设置输出格式
                val encoder = createEncoderOutputFileParameters(
                    inputFormat,
                    outputFormat,
                    newBitrate,
                )
                val inputSurface: InputSurface
                val outputSurface: OutputSurface
                try {
                    var inputDone = false
                    var outputDone = false
                    var videoTrackIndex = -5
                    inputSurface = InputSurface(encoder.createInputSurface())
                    inputSurface.makeCurrent()
                    //开始执行编码
                    encoder.start()
                    outputSurface = OutputSurface()
                    decoder = prepareDecoder(inputFormat, outputSurface)
                    //开始执行解码
                    decoder.start()
                    while (!outputDone) {
                        if (!inputDone) {
                            val index = extractor.sampleTrackIndex
                            if (index == videoIndex) {
                                val inputBufferIndex =
                                    decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT)
                                if (inputBufferIndex >= 0) {
                                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                                    val chunkSize = extractor.readSampleData(inputBuffer!!, 0)
                                    when {
                                        chunkSize < 0 -> {
                                            decoder.queueInputBuffer(
                                                inputBufferIndex,
                                                0,
                                                0,
                                                0L,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                            )
                                            inputDone = true
                                        }

                                        else -> {
                                            val sampleTime = extractor.sampleTime
                                            // **当超过 endTime 时停止读取**
                                            if (sampleTime > endTime) {
                                                decoder.queueInputBuffer(
                                                    inputBufferIndex,
                                                    0,
                                                    0,
                                                    0L,
                                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                                )
                                                inputDone = true
                                            } else {
                                                decoder.queueInputBuffer(
                                                    inputBufferIndex,
                                                    0,
                                                    chunkSize,
                                                    sampleTime,
                                                    0
                                                )
                                                extractor.advance()
                                            }
                                        }
                                    }
                                }

                            } else if (index == -1) { //end of file
                                val inputBufferIndex =
                                    decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT)
                                if (inputBufferIndex >= 0) {
                                    decoder.queueInputBuffer(
                                        inputBufferIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                }
                            }
                        }

                        var decoderOutputAvailable = true
                        var encoderOutputAvailable = true

                        loop@ while (decoderOutputAvailable || encoderOutputAvailable) {
                            //取消任务
                            if (!isRunning) {
                                dispose(
                                    videoIndex,
                                    decoder,
                                    encoder,
                                    inputSurface,
                                    outputSurface,
                                    extractor
                                )

                                compressionProgressListener.onProgressCancelled(id)
                                return Result(
                                    id,
                                    success = false,
                                    failureMessage = "The compression has stopped!"
                                )
                            }
                            //Encoder 编码
                            val encoderStatus =
                                encoder.dequeueOutputBuffer(bufferInfo, MEDIACODEC_TIMEOUT_DEFAULT)
                            when (encoderStatus) {
                                //系统还没有准备好输出缓冲区，因此你需要等待再次尝试
                                MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable = false
                                //编码器已经准备好输出数据，且输出格式可能已经发生变化。这时候你应该通过 encoder.getOutputFormat() 或 decoder.getOutputFormat() 获取新的格式信息。
                                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val newFormat = encoder.outputFormat
                                    if (videoTrackIndex == -5)
                                        videoTrackIndex = mediaMuxer.addTrack(newFormat, false)
                                }
                                //输出缓冲区的集合发生了变化。通常，这个状态不会直接影响数据的处理，而是表示缓冲区管理的方式发生了变化。
                                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                                    // ignore this status
                                }

                                else -> {
                                    if (encoderStatus < 0) {
                                        throw RuntimeException("unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")
                                    }
                                    val encodedData = encoder.getOutputBuffer(encoderStatus)
                                        ?: throw RuntimeException("encoderOutputBuffer $encoderStatus was null")

                                    if (bufferInfo.size > 1) {
                                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                            mediaMuxer.writeSampleData(
                                                videoTrackIndex,
                                                encodedData, bufferInfo, false
                                            )
                                        }
                                    }
                                    outputDone =
                                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    encoder.releaseOutputBuffer(encoderStatus, false)
                                }
                            }
                            if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) continue@loop

                            //Decoder 解码
                            val decoderStatus =
                                decoder.dequeueOutputBuffer(bufferInfo, MEDIACODEC_TIMEOUT_DEFAULT)
                            when (decoderStatus) {
                                MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOutputAvailable = false
                                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
                                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    // ignore this status
                                }

                                else -> {
                                    if (decoderStatus < 0) {
                                        throw RuntimeException("unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")
                                    }
                                    val doRender = bufferInfo.size != 0
                                    decoder.releaseOutputBuffer(decoderStatus, doRender)
                                    if (doRender) {
                                        var errorWait = false
                                        try {
                                            outputSurface.awaitNewImage()
                                        } catch (e: Exception) {
                                            errorWait = true
                                            Log.e(
                                                "Compressor",
                                                e.message ?: "Compression failed at swapping buffer"
                                            )
                                        }
                                        if (!errorWait) {
                                            outputSurface.drawImage()
                                            inputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                                            val preT = bufferInfo.presentationTimeUs.toFloat()
                                            val durationT = duration.toFloat()
                                            val p = (preT - startTime) / durationT * 100
                                            compressionProgressListener.onProgressChanged(id, p)
                                            inputSurface.swapBuffers()
                                        }
                                    }
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        decoderOutputAvailable = false
                                        encoder.signalEndOfInputStream()
                                    }
                                }
                            }
                        }
                    }

                } catch (exception: Exception) {
                    printException(exception)
                    return Result(id, success = false, failureMessage = exception.message)
                }
                dispose(
                    videoIndex,
                    decoder,
                    encoder,
                    inputSurface,
                    outputSurface,
                    extractor
                )
                processAudio(
                    mediaMuxer = mediaMuxer,
                    bufferInfo = bufferInfo,
                    disableAudio = disableAudio,
                    extractor, startTime, endTime
                )
                extractor.release()
                try {
                    mediaMuxer.finishMovie()
                } catch (e: Exception) {
                    printException(e)
                }

            } catch (exception: Exception) {
                printException(exception)
            }
            var resultFile = cacheFile
            streamableFile?.let {
                try {
                    val result = StreamableVideo.start(`in` = cacheFile, out = File(it))
                    resultFile = File(it)
                    if (result && cacheFile.exists()) {
                        cacheFile.delete()
                    }
                } catch (e: Exception) {
                    printException(e)
                }
            }
            return Result(
                id,
                success = true,
                failureMessage = null,
                size = resultFile.length(),
                resultFile.path
            )
        }
        return Result(
            id,
            success = false,
            failureMessage = "Something went wrong, please try again"
        )
    }

    private fun processAudio(
        mediaMuxer: MP4Builder,
        bufferInfo: MediaCodec.BufferInfo,
        disableAudio: Boolean,
        extractor: MediaExtractor,
        startTime: Long,
        endTime: Long,
    ) {
        val audioIndex = findTrack(extractor, isVideo = false)
        if (audioIndex >= 0 && !disableAudio) {
            extractor.selectTrack(audioIndex)
            val audioFormat = extractor.getTrackFormat(audioIndex)
            val muxerTrackIndex = mediaMuxer.addTrack(audioFormat, true)
            var maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)

            if (maxBufferSize <= 0) {
                maxBufferSize = 64 * 1024
            }

            var buffer: ByteBuffer = ByteBuffer.allocateDirect(maxBufferSize)
            if (Build.VERSION.SDK_INT >= 28) {
                val size = extractor.sampleSize
                if (size > maxBufferSize) {
                    maxBufferSize = (size + 1024).toInt()
                    buffer = ByteBuffer.allocateDirect(maxBufferSize)
                }
            }
            var inputDone = false
            extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            while (!inputDone) {
                val index = extractor.sampleTrackIndex
                if (index == audioIndex) {
                    bufferInfo.size = extractor.readSampleData(buffer, 0)

                    if (bufferInfo.size >= 0) {
                        bufferInfo.apply {
                            presentationTimeUs = extractor.sampleTime
                            offset = 0
                            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        // 判断音频样本时间是否已经超过结束时间，若超过则停止处理
                        if (bufferInfo.presentationTimeUs >= endTime) {
                            bufferInfo.size = 0
                            inputDone = true
                        } else {
                            mediaMuxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo, true)
                            extractor.advance()
                        }

                    } else {
                        bufferInfo.size = 0
                        inputDone = true
                    }
                } else if (index == -1) {
                    inputDone = true
                }
            }
            extractor.unselectTrack(audioIndex)
        }
    }

    private fun prepareEncoder(hasQTI: Boolean): MediaCodec {

        // This seems to cause an issue with certain phones
        // val encoderName = MediaCodecList(REGULAR_CODECS).findEncoderForFormat(outputFormat)
        // val encoder: MediaCodec = MediaCodec.createByCodecName(encoderName)
        // Log.i("encoderName", encoder.name)
        // c2.qti.avc.encoder results in a corrupted .mp4 video that does not play in
        // Mac and iphones
        return if (hasQTI) {
            Log.d("openking","prepare use hardware - c2.android.avc.encoder")
            MediaCodec.createByCodecName("c2.android.avc.encoder")
        } else {
            Log.d("openking","prepare use default encoder ")
            MediaCodec.createEncoderByType(MIME_TYPE)
        }
    }

    private fun prepareDecoder(
        inputFormat: MediaFormat,
        outputSurface: OutputSurface,
    ): MediaCodec {
        // This seems to cause an issue with certain phones
        // val decoderName =
        //    MediaCodecList(REGULAR_CODECS).findDecoderForFormat(inputFormat)
        // val decoder = MediaCodec.createByCodecName(decoderName)
        // Log.i("decoderName", decoder.name)

        // val decoder = if (hasQTI) {
        // MediaCodec.createByCodecName("c2.android.avc.decoder")
        //} else {

        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        //}

        decoder.configure(inputFormat, outputSurface.getSurface(), null, 0)

        return decoder
    }

    private fun dispose(
        videoIndex: Int,
        decoder: MediaCodec,
        encoder: MediaCodec,
        inputSurface: InputSurface,
        outputSurface: OutputSurface,
        extractor: MediaExtractor
    ) {
        extractor.unselectTrack(videoIndex)

        decoder.stop()
        decoder.release()

        encoder.stop()
        encoder.release()

        inputSurface.release()
        outputSurface.release()
    }
}
