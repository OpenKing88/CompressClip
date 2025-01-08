package com.openking.compressclip.config

import com.openking.compressclip.utils.CompressorUtils

fun interface VideoResizer {
    companion object {
        /**
         * 根据原始宽度和高度缩小视频分辨率。
         * - 50% If the width or height is greater than or equal to 1920 pixels.
         * - 75% If the width or height is greater than or equal to 1280 pixels.
         * - 95% If the width or height is greater than or equal to 960 pixels.
         * - 90% If the width and height are both less than 960 pixels.
         */
        @JvmStatic
        val auto: VideoResizer = ScaleResize(null);

        /**
         * 按给定的比例调整视频尺寸
         */
        @JvmStatic
        fun scale(value: Double): VideoResizer = ScaleResize(value)

        /**
         * 如果宽度或高度大于limit，则缩小视频比例，保留视频的宽高比.
         * @param limit The maximum width and height of the video
         */
        @JvmStatic
        fun limitSize(limit: Double): VideoResizer = LimitDimension(limit, limit)

        /**
         * 如果宽度或高度大于 [maxWidth] 或 [maxHeight]，则缩放视频，但保留视频的宽高比。
         * @param maxWidth The maximum width of the video
         * @param maxHeight The maximum height of the video
         */
        @JvmStatic
        fun limitSize(maxWidth: Double, maxHeight: Double): VideoResizer = LimitDimension(maxWidth, maxHeight)

        /**
         * 缩放视频，使宽度和高度与 [size] 匹配，保留视频的宽高比。
         * @param size The target width/height of the video
         */
        @JvmStatic
        fun matchSize(size: Double, stretch: Boolean = false): VideoResizer = MatchDimension(size, size, stretch)

        /**
         * 缩放视频，使宽度与 [width] 匹配，高度与 [height] 匹配，保留视频的宽高比。
         * @param width The target width of the video
         * @param height The target height of the video
         */
        @JvmStatic
        fun matchSize(width: Double, height: Double, stretch: Boolean = false): VideoResizer = MatchDimension(width, height, stretch)

        private fun keepAspect(width: Double, height: Double, newWidth: Double, newHeight: Double): Pair<Double, Double> {
            val desiredAspect = width / height
            val videoAspect = newWidth / newHeight
            return if (videoAspect <= desiredAspect) Pair(newWidth, newWidth / desiredAspect) else Pair(newHeight * desiredAspect, newHeight)
        }
    }

    fun resize(width: Double, height: Double): Pair<Double, Double>

    private class LimitDimension(private val width: Double, private val height: Double) : VideoResizer {
        override fun resize(width: Double, height: Double): Pair<Double, Double> {
            return if (width < this.width && height < this.height) Pair(width, height) else keepAspect(width, height, this.width, this.height)
        }
    }

    private class MatchDimension(private val width: Double, private val height: Double, private val stretch: Boolean) : VideoResizer {
        override fun resize(width: Double, height: Double): Pair<Double, Double> {
            return if (stretch) Pair(this.width, this.height) else keepAspect(width, height, this.width, this.height)
        }
    }

    private class ScaleResize(private val percentage: Double? = null) : VideoResizer {
        override fun resize(width: Double, height: Double): Pair<Double, Double> {
            val p = percentage ?: CompressorUtils.autoResizePercentage(width, height)
            return Pair(width * p, height * p)
        }
    }
}