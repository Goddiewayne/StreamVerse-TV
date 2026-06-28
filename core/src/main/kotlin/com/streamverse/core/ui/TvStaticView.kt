package com.streamverse.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.random.Random

/**
 * Authentic analogue television static ("snow") — the no-signal screen for a premium TV
 * experience. Shown whenever a live stream cannot start or unexpectedly drops, so the player
 * never falls back to a lifeless black rectangle.
 *
 * ### Why a plain [View] in :core (not Compose / not AGSL)
 * - It is used by BOTH the Compose mobile app (wrapped in `AndroidView`) and the View-based TV
 *   activity, so a single framework [View] is the only thing both can share without duplication.
 * - `RuntimeShader` (AGSL) would be the obvious GPU path but needs API 33+, while this app ships
 *   to Fire TV / low-end phones on minSdk 21. So instead we pre-bake a small pool of grayscale
 *   noise [Bitmap]s ONCE per size, then each frame just blits one (scaled to fill) with a couple
 *   of cheap transforms. The per-frame cost is a single hardware-composited `drawBitmap` — no
 *   per-pixel work, no allocations — which is smooth even on weak GPUs and easy on the battery.
 *
 * ### Authenticity
 * Real TV snow isn't "random noise every pixel every frame". It is a fast shuffle of a few grain
 * fields plus slow analogue artefacts. We reproduce that with: a rotating tile pool (grain
 * movement), per-frame luminance flicker (alpha), sub-pixel horizontal jitter with the occasional
 * larger jump (horizontal instability), a faint bright bar that rolls down the screen (vertical
 * sync roll), and very subtle scanlines. No digital glitch, RGB split, or VHS tracking — those
 * read as "broken app", not "lost signal".
 *
 * The view animates only while attached AND visible, and recycles its bitmaps on detach, so it is
 * leak-free and costs nothing when off-screen.
 */
class TvStaticView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Visual strength / cost trade-off. [LOW] doubles as a low-power mode (fewer fps, calmer). */
    enum class Intensity(
        /** Target frames per second. Snow reads as "alive" from ~20fps; capping saves battery. */
        val fps: Int,
        /** Number of pre-baked grain fields shuffled between. More = less obvious repetition. */
        val tileCount: Int,
        /** Longer edge of each grain tile in px before it is upscaled to fill. */
        val tileSize: Int,
        /** Peak luminance flicker amplitude (0f..1f). */
        val flicker: Float,
        /** Max horizontal jitter as a fraction of width. */
        val jitter: Float,
        /** Whether the rolling sync bar + scanlines are drawn. */
        val artefacts: Boolean,
    ) {
        LOW(fps = 18, tileCount = 4, tileSize = 200, flicker = 0.05f, jitter = 0.004f, artefacts = false),
        MEDIUM(fps = 24, tileCount = 6, tileSize = 280, flicker = 0.08f, jitter = 0.006f, artefacts = true),
        HIGH(fps = 30, tileCount = 8, tileSize = 360, flicker = 0.10f, jitter = 0.010f, artefacts = true);

        companion object {
            fun fromKey(key: String?): Intensity = when (key?.lowercase()) {
                "low" -> LOW
                "high" -> HIGH
                else -> MEDIUM
            }
        }
    }

    var intensity: Intensity = Intensity.MEDIUM
        set(value) {
            if (field != value) {
                field = value
                tiles = emptyArray()      // force a rebuild at the new tile count/size
                if (width > 0 && height > 0) buildTiles(width, height)
            }
        }

    private val rng = Random(System.nanoTime())
    private val tilePaint = Paint().apply {
        // Crisp grain dots — bilinear filtering would smear the snow into mush.
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val barPaint = Paint().apply { isAntiAlias = false }
    private val scanlinePaint = Paint()

    private var tiles: Array<Bitmap> = emptyArray()
    private val srcRect = Rect()
    private val dstRect = Rect()

    // Rolling sync bar — a faint bright horizontal band drifting down the screen.
    private var barY = 0f
    private var scanlineShader: BitmapShader? = null
    private var scanlineBitmap: Bitmap? = null

    // Frame pacing: Choreographer fires at the display refresh rate; we down-sample to [fps].
    private var lastFrameNanos = 0L
    private var running = false
    private var tileCursor = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val frameIntervalNanos = 1_000_000_000L / intensity.fps
            if (frameTimeNanos - lastFrameNanos >= frameIntervalNanos) {
                lastFrameNanos = frameTimeNanos
                advance()
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        // The static IS the background — no need for the system to clear behind it.
        setWillNotDraw(false)
    }

    // ── Lifecycle: animate only when actually on screen ──────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startIfVisible()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
        recycleTiles()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        startIfVisible()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        startIfVisible()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) buildTiles(w, h)
    }

    private fun startIfVisible() {
        val visible = isAttachedToWindow && isShown && visibility == VISIBLE
        if (visible) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // ── Tile pool ────────────────────────────────────────────────────────────────────────

    /**
     * Pre-bakes the grain fields for the current size. Each tile is a downscaled grayscale noise
     * field that gets upscaled to fill on draw, so generation stays cheap (a few hundred-thousand
     * pixel writes total) and happens off the per-frame path.
     */
    private fun buildTiles(w: Int, h: Int) {
        recycleTiles()
        val aspect = h.toFloat() / w.toFloat()
        val tileW = intensity.tileSize.coerceAtMost(w)
        val tileH = (tileW * aspect).toInt().coerceAtLeast(1)
        tiles = Array(intensity.tileCount) { generateNoiseTile(tileW, tileH) }
        srcRect.set(0, 0, tileW, tileH)
        if (intensity.artefacts) buildScanlines()
    }

    private fun generateNoiseTile(w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            // Uniform grayscale snow with an occasional pure black/white speckle for sparkle.
            val g = when (rng.nextInt(32)) {
                0 -> 0
                1 -> 255
                else -> rng.nextInt(256)
            }
            pixels[i] = Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** A 1×4 tile (3 transparent rows, 1 faint-dark row) repeated to give subtle CRT scanlines. */
    private fun buildScanlines() {
        scanlineBitmap?.recycle()
        val bmp = Bitmap.createBitmap(1, 4, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.argb(40, 0, 0, 0))
        bmp.setPixel(0, 1, Color.TRANSPARENT)
        bmp.setPixel(0, 2, Color.TRANSPARENT)
        bmp.setPixel(0, 3, Color.TRANSPARENT)
        scanlineBitmap = bmp
        scanlineShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        scanlinePaint.shader = scanlineShader
        scanlinePaint.isFilterBitmap = false
    }

    private fun recycleTiles() {
        tiles.forEach { it.recycle() }
        tiles = emptyArray()
        scanlineBitmap?.recycle()
        scanlineBitmap = null
        scanlineShader = null
        scanlinePaint.shader = null
    }

    // ── Per-frame state ────────────────────────────────────────────────────────────────────

    private fun advance() {
        // Shuffle grain: usually step to the next field, occasionally jump for a less periodic feel.
        tileCursor = if (rng.nextInt(5) == 0) rng.nextInt(tiles.size.coerceAtLeast(1))
                     else (tileCursor + 1) % tiles.size.coerceAtLeast(1)
        // Roll the sync bar slowly down and wrap.
        if (intensity.artefacts) {
            barY += height * 0.012f
            if (barY > height) barY = -height * 0.15f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (tiles.isEmpty()) {
            // Not yet baked (or recycled): a neutral mid-grey reads as "snow about to appear",
            // never a black flash.
            canvas.drawColor(Color.rgb(28, 28, 28))
            return
        }

        // Luminance flicker via paint alpha — the whole field breathes slightly each frame.
        val flicker = 1f - rng.nextFloat() * intensity.flicker
        tilePaint.alpha = (255 * flicker).toInt().coerceIn(0, 255)

        // Horizontal instability: overscan the bitmap slightly and offset it so jitter never
        // exposes an empty edge. Mostly tiny sub-pixel drift, with a rare larger "tear".
        val maxJitter = (w * intensity.jitter)
        val jitter = if (rng.nextInt(40) == 0) (rng.nextFloat() - 0.5f) * maxJitter * 4f
                     else (rng.nextFloat() - 0.5f) * maxJitter * 2f
        val overscan = (maxJitter * 4f).toInt() + 2
        val dx = jitter.toInt()
        dstRect.set(-overscan + dx, 0, w + overscan + dx, h)

        canvas.drawBitmap(tiles[tileCursor], srcRect, dstRect, tilePaint)

        if (intensity.artefacts) {
            // Faint bright bar drifting down — the analogue vertical-sync roll.
            val barH = h * 0.06f
            barPaint.color = Color.argb(30, 255, 255, 255)
            canvas.drawRect(0f, barY, w.toFloat(), barY + barH, barPaint)
            // Subtle scanlines over everything.
            scanlineShader?.let { canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scanlinePaint) }
        }
    }
}
