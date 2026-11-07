package org.lara.app.data.repository

import java.io.File
import java.io.IOException
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Clockwise normalized corners: top left, top right, bottom right, bottom left. */
object DocumentScanner {
    val available: Boolean by lazy {
        try { OpenCVLoader.initLocal().also { if (it) Core.setNumThreads(2) } }
        catch (_: LinkageError) { false }
        catch (_: Exception) { false }
    }
    fun fullPage() = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    fun valid(corners: FloatArray): Boolean {
        if (corners.size != 8 || corners.any { !it.isFinite() || it !in 0f..1f }) return false
        var area = 0f
        for (index in 0..3) {
            val next = (index + 1) % 4
            val after = (index + 2) % 4
            val cross = (corners[next * 2] - corners[index * 2]) * (corners[after * 2 + 1] - corners[next * 2 + 1]) -
                (corners[next * 2 + 1] - corners[index * 2 + 1]) * (corners[after * 2] - corners[next * 2])
            if (cross <= 0.001f) return false
            area += corners[index * 2] * corners[next * 2 + 1] - corners[next * 2] * corners[index * 2 + 1]
        }
        return area >= 0.1f
    }

    suspend fun detect(source: File): FloatArray? = withContext(Dispatchers.IO) {
        check(available)
        val image = Imgcodecs.imread(source.path, Imgcodecs.IMREAD_GRAYSCALE)
        try { detectGray(image) } finally { image.release() }
    }

    private suspend fun detectGray(image: Mat): FloatArray? {
        val small = Mat()
        val edges = Mat()
        val localContrast = Mat()
        val paper = Mat()
        val noMask = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            require(image.cols() > 1 && image.rows() > 1 && image.total() <= 1920L * 1080)
            val scale = min(1.0, 640.0 / maxOf(image.cols(), image.rows()))
            Imgproc.resize(image, small, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
            val contrast = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            try { contrast.apply(small, localContrast) }
            finally { contrast.collectGarbage(); contrast.clear() }
            Imgproc.Canny(localContrast, edges, 40.0, 100.0)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val otsu = Imgproc.threshold(small, paper, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val thresholds = listOf(otsu, minOf(otsu, lowerQuartile(small, noMask) + 15),
                minOf(255.0, otsu + 32), minOf(255.0, otsu + 48))
            for (threshold in thresholds.distinct()) {
                val paperContours = mutableListOf<MatOfPoint>()
                try {
                    Imgproc.threshold(small, paper, threshold, 255.0, Imgproc.THRESH_BINARY)
                    Imgproc.findContours(paper, paperContours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    contours.addAll(paperContours)
                } catch (error: Throwable) {
                    paperContours.forEach { it.release() }
                    throw error
                }
            }
            var best: FloatArray? = null
            var bestArea = small.total() * 0.25
            for (contour in contours) {
                currentCoroutineContext().ensureActive()
                val hull = MatOfInt()
                val curve = MatOfPoint2f()
                val polygon = MatOfPoint2f()
                try {
                    Imgproc.convexHull(contour, hull)
                    val boundary = contour.toArray()
                    curve.fromArray(*hull.toArray().map { boundary[it] }.toTypedArray())
                    Imgproc.approxPolyDP(curve, polygon, Imgproc.arcLength(curve, true) * 0.02, true)
                    var points = polygon.toArray()
                    if (points.size != 4) {
                        points = Array(4) { Point() }
                        Imgproc.minAreaRect(curve).points(points)
                    }
                    if (points.any { it.x !in 0.0..(small.cols() - 1).toDouble() ||
                        it.y !in 0.0..(small.rows() - 1).toDouble() }) continue
                    val centerX = points.sumOf { it.x } / 4
                    val centerY = points.sumOf { it.y } / 4
                    val ordered = points.sortedBy { kotlin.math.atan2(it.y - centerY, it.x - centerX) }
                    val first = ordered.indices.minBy { ordered[it].x + ordered[it].y }
                    val corners = FloatArray(8)
                    for (index in 0..3) {
                        val point = ordered[(first + index) % 4]
                        corners[index * 2] = (point.x / (small.cols() - 1)).toFloat().coerceIn(0f, 1f)
                        corners[index * 2 + 1] = (point.y / (small.rows() - 1)).toFloat().coerceIn(0f, 1f)
                    }
                    val area = kotlin.math.abs(Imgproc.contourArea(curve))
                    if (area > bestArea && valid(corners) && hasPaperContrast(small, corners) && hasPageEdges(small, corners)) {
                        best = corners
                        bestArea = area
                    }
                } finally { hull.release(); curve.release(); polygon.release() }
            }
            return best
        } finally {
            contours.forEach { it.release() }
            small.release(); edges.release(); localContrast.release(); paper.release(); noMask.release(); hierarchy.release()
        }
    }

    private fun hasPageEdges(gray: Mat, corners: FloatArray): Boolean {
        val pixels = ByteArray(gray.total().toInt())
        gray.get(0, 0, pixels)
        val supported = IntArray(4)
        for (side in 0..3) {
            val next = (side + 1) % 4
            val x = corners[side * 2] * (gray.cols() - 1)
            val y = corners[side * 2 + 1] * (gray.rows() - 1)
            val dx = corners[next * 2] * (gray.cols() - 1) - x
            val dy = corners[next * 2 + 1] * (gray.rows() - 1) - y
            val length = hypot(dx, dy)
            val offset = min(8f, length * .04f)
            for (sample in 1..15) {
                val position = .1f + sample * .05f
                val innerX = (x + dx * position - dy / length * offset).roundToInt()
                val innerY = (y + dy * position + dx / length * offset).roundToInt()
                val outerX = (x + dx * position + dy / length * offset).roundToInt()
                val outerY = (y + dy * position - dx / length * offset).roundToInt()
                if (innerX !in 0 until gray.cols() || outerX !in 0 until gray.cols() ||
                    innerY !in 0 until gray.rows() || outerY !in 0 until gray.rows()) continue
                val inside = pixels[innerY * gray.cols() + innerX].toInt() and 255
                val outside = pixels[outerY * gray.cols() + outerX].toInt() and 255
                if (inside - outside >= 8) supported[side]++
            }
        }
        return supported.all { it >= 8 }
    }

    private fun hasPaperContrast(gray: Mat, corners: FloatArray): Boolean {
        val inside = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val outside = Mat()
        val brightPaper = Mat()
        val polygon = MatOfPoint(*(0..3).map {
            Point((corners[it * 2] * (gray.cols() - 1)).toDouble(), (corners[it * 2 + 1] * (gray.rows() - 1)).toDouble())
        }.toTypedArray())
        try {
            Imgproc.fillConvexPoly(inside, polygon, Scalar(255.0))
            Core.bitwise_not(inside, outside)
            val outsidePixels = Core.countNonZero(outside)
            if (outsidePixels < 64) return false
            // Bright desk objects must not make shadowed paper look darker than its background.
            val background = lowerQuartile(gray, outside)
            Core.compare(gray, Scalar(background + 10.0), brightPaper, Core.CMP_GT)
            Core.bitwise_and(brightPaper, inside, brightPaper)
            return Core.countNonZero(brightPaper) >= Core.countNonZero(inside) * 0.65
        } finally {
            inside.release(); outside.release(); brightPaper.release(); polygon.release()
        }
    }

    private fun lowerQuartile(gray: Mat, mask: Mat): Double {
        val histogram = Mat()
        val channel = MatOfInt(0)
        val bins = MatOfInt(256)
        val range = MatOfFloat(0f, 256f)
        try {
            Imgproc.calcHist(listOf(gray), channel, mask, histogram, bins, range)
            val counts = FloatArray(256)
            histogram.get(0, 0, counts)
            val pixels = if (mask.empty()) gray.total().toDouble() else Core.countNonZero(mask).toDouble()
            var cumulative = 0f
            for (level in counts.indices) {
                cumulative += counts[level]
                if (cumulative >= pixels * 0.25) return level.toDouble()
            }
            return 255.0
        } finally { histogram.release(); channel.release(); bins.release(); range.release() }
    }

    suspend fun render(source: File, corners: FloatArray, enhance: Boolean, directory: File): File = withContext(Dispatchers.IO) {
        require(valid(corners))
        check(available)
        val image = Imgcodecs.imread(source.path, Imgcodecs.IMREAD_COLOR)
        val warped = Mat()
        val lab = Mat()
        val channels = mutableListOf<Mat>()
        val smallBackground = Mat()
        val background = Mat()
        val correctedLight = Mat()
        val neutralMask = Mat()
        val sourcePoints = MatOfPoint2f()
        val targetPoints = MatOfPoint2f()
        var transform: Mat? = null
        var output: File? = null
        try {
            require(image.cols() > 1 && image.rows() > 1 && image.total() <= 1920L * 1080)
            val points = (0..3).map { Point(corners[it * 2] * (image.cols() - 1).toDouble(), corners[it * 2 + 1] * (image.rows() - 1).toDouble()) }
            fun distance(a: Int, b: Int) = hypot(points[a].x - points[b].x, points[a].y - points[b].y)
            val width = maxOf(distance(0, 1), distance(3, 2))
            val height = maxOf(distance(0, 3), distance(1, 2))
            val scale = minOf(1.0, 1920.0 / maxOf(width, height), 1080.0 / minOf(width, height))
            val columns = (width * scale).roundToInt().coerceAtLeast(2)
            val rows = (height * scale).roundToInt().coerceAtLeast(2)
            sourcePoints.fromArray(*points.toTypedArray())
            targetPoints.fromArray(Point(0.0, 0.0), Point((columns - 1).toDouble(), 0.0),
                Point((columns - 1).toDouble(), (rows - 1).toDouble()), Point(0.0, (rows - 1).toDouble()))
            transform = Imgproc.getPerspectiveTransform(sourcePoints, targetPoints)
            Imgproc.warpPerspective(image, warped, transform, Size(columns.toDouble(), rows.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            currentCoroutineContext().ensureActive()
            if (enhance) {
                Imgproc.cvtColor(warped, lab, Imgproc.COLOR_BGR2Lab)
                Core.split(lab, channels)
                Imgproc.resize(channels[0], smallBackground, Size(32.0, 32.0), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.GaussianBlur(smallBackground, smallBackground, Size(0.0, 0.0), 3.0)
                Imgproc.resize(smallBackground, background, warped.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                Core.max(background, Scalar(128.0), background)
                Core.divide(channels[0], background, correctedLight, 245.0)
                Core.addWeighted(correctedLight, 0.75, channels[0], 0.25, 0.0, correctedLight)
                // Flatten paper shadows while retaining the luminance of colored drawings and ink.
                Core.inRange(lab, Scalar(0.0, 113.0, 113.0), Scalar(255.0, 143.0, 143.0), neutralMask)
                correctedLight.copyTo(channels[0], neutralMask)
                val contrast = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                try { contrast.apply(channels[0], channels[0]) }
                finally { contrast.collectGarbage(); contrast.clear() }
                Core.merge(channels, lab)
                Imgproc.cvtColor(lab, warped, Imgproc.COLOR_Lab2BGR)
            }
            currentCoroutineContext().ensureActive()
            check(directory.isDirectory || directory.mkdirs())
            val result = File.createTempFile("scan-preview-", ".png", directory)
            output = result
            if (!Imgcodecs.imwrite(result.path, warped)) throw IOException("Scan could not be saved")
            result
        } catch (error: Throwable) {
            output?.delete()
            throw error
        } finally {
            channels.forEach { it.release() }
            smallBackground.release(); background.release(); correctedLight.release(); neutralMask.release()
            image.release(); warped.release(); lab.release(); sourcePoints.release(); targetPoints.release(); transform?.release()
        }
    }
}
