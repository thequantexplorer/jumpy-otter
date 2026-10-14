package com.devin.jumpyotter

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/** Chunky arcade text: a thick ink outline drawn under the fill, plus a drop shadow. */
class ArcadeTextView(context: Context) : TextView(context) {
    var outlineColor: Int = Palette.hudInk.toArgb()
    var outlineWidthPx: Float = 0f
    private var fillColor: Int = Color.WHITE

    init {
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        setShadowLayer(2f, 0f, 3f, Color.argb(150, 0, 0, 0))
    }

    fun setFill(color: Int) {
        fillColor = color
        super.setTextColor(color)
    }

    override fun setTextColor(color: Int) {
        fillColor = color
        super.setTextColor(color)
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidthPx > 0f) {
            val p = paint
            val prevStyle = p.style
            val prevWidth = p.strokeWidth
            p.style = Paint.Style.STROKE
            p.strokeWidth = outlineWidthPx
            p.strokeJoin = Paint.Join.ROUND
            super.setTextColor(outlineColor)
            super.onDraw(canvas)
            p.style = prevStyle
            p.strokeWidth = prevWidth
            super.setTextColor(fillColor)
        }
        super.onDraw(canvas)
    }
}

class MainActivity : Activity(), GameHUD {

    private lateinit var game: GameController
    private lateinit var glView: GLSurfaceView
    private lateinit var sound: SoundManager
    private lateinit var gestures: GestureDetector

    // HUD
    private lateinit var scoreCard: View
    private lateinit var scoreLabel: ArcadeTextView
    private lateinit var creatineCard: View
    private lateinit var creatineLabel: ArcadeTextView
    private lateinit var rivalStack: LinearLayout
    private val rivalChips = HashMap<Int, Pair<View, ArcadeTextView>>()
    private lateinit var bannerLabel: ArcadeTextView
    private lateinit var flashView: View
    private lateinit var comboLabel: ArcadeTextView
    private lateinit var toastLabel: ArcadeTextView
    private lateinit var pauseButton: View
    private lateinit var pauseOverlay: FrameLayout
    private lateinit var soundButton: ArcadeTextView
    private lateinit var rootView: View

    // overlays
    private lateinit var titleOverlay: LinearLayout
    private lateinit var hiScoreLabel: ArcadeTextView
    private lateinit var lifetimeLabel: ArcadeTextView
    private lateinit var logoColumn: LinearLayout
    private lateinit var boardCard: LinearLayout
    private val boardRows = ArrayList<ArcadeTextView>()
    private var showingBoard = false
    private lateinit var skinCard: LinearLayout
    private lateinit var skinLabel: ArcadeTextView
    private lateinit var skinHint: ArcadeTextView
    private lateinit var placementLabel: ArcadeTextView
    private val attractTick = object : Runnable {
        override fun run() {
            advanceAttract()
            rootView.postDelayed(this, 4500)
        }
    }
    private lateinit var gameOverPanel: LinearLayout
    private lateinit var rankPill: TextView
    private lateinit var finalScoreLabel: ArcadeTextView
    private lateinit var bestLabel: ArcadeTextView
    private var countUp: ValueAnimator? = null
    private var canRetry = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sound = SoundManager(this)
        val autopilot = intent.getBooleanExtra("AUTOPILOT", false)
        val relay = intent.getStringExtra("RELAY_URL") ?: MultiplayerClient.DEFAULT_URL
        val prefs = getSharedPreferences("jumpyotter", MODE_PRIVATE)
        game = GameController(prefs, sound, autopilot, relay)

        val root = FrameLayout(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = game.onSurfaceCreated()
                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = game.onSurfaceChanged(width, height)
                override fun onDrawFrame(gl: GL10?) = game.onDrawFrame()
            })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView, FrameLayout.LayoutParams(MATCH, MATCH))
        setupVignette(root)
        setupHUD(root)
        setupOverlays(root)
        setupPause(root)
        setContentView(root)
        rootView = root
        setupGestures(root)
        hideSystemBars()
        game.hud = this
        game.presentTitle()
        root.postDelayed(attractTick, 4500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        game.setPaused(true)
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        rootView.removeCallbacks(attractTick)
        countUp?.cancel()
        game.destroy()
        sound.release()
        super.onDestroy()
    }

    // MARK: - Building blocks

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun arcade(sizeSp: Float, fill: Rgb, text: String = "", outlineDp: Float = 3f, letterSpacing: Float = 0f): ArcadeTextView =
        ArcadeTextView(this).apply {
            this.text = text
            textSize = sizeSp
            setFill(fill.toArgb())
            outlineWidthPx = dpf(outlineDp)
            this.letterSpacing = letterSpacing
            gravity = Gravity.CENTER
        }

    private fun caption(text: String, fill: Rgb = Palette.hudSilver): ArcadeTextView =
        arcade(11f, fill, text, outlineDp = 0f, letterSpacing = 0.25f).apply {
            setShadowLayer(0f, 0f, 0f, 0)
        }

    private fun cardBackground(fill: Rgb, border: Rgb, radiusDp: Float = 16f, alpha: Float = 0.92f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpf(radiusDp)
            setColor(fill.toArgb(alpha))
            setStroke(dp(3f), border.toArgb())
        }

    private fun pillBackground(fill: Int, radiusDp: Float = 999f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpf(radiusDp)
            setColor(fill)
        }

    private fun setupVignette(root: FrameLayout) {
        val vignette = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientCenter(0.5f, 0.5f)
                gradientRadius = dpf(420f)
                colors = intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(120, 0, 0, 0))
            }
            isClickable = false
        }
        root.addView(vignette, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // MARK: - HUD

    private fun setupHUD(root: FrameLayout) {
        // score card
        val scoreStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground(Palette.hudNavy, Palette.hotOrange)
            setPadding(dp(18f), dp(8f), dp(18f), dp(10f))
            elevation = dpf(6f)
        }
        scoreStack.addView(caption("SCORE"), wrap())
        scoreLabel = arcade(40f, Palette.hudCream, "0", outlineDp = 3f).apply {
            contentDescription = "score"
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        scoreStack.addView(scoreLabel, wrap())
        scoreCard = scoreStack
        root.addView(scoreCard, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(28f); leftMargin = dp(16f)
        })

        // creatine card
        val creatineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBackground(Palette.hudNavy, Palette.accentGold, radiusDp = 14f)
            setPadding(dp(12f), dp(6f), dp(14f), dp(6f))
            elevation = dpf(6f)
        }
        val icon = TextView(this).apply {
            text = "C"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = pillBackground(Palette.cableRed.toArgb(), radiusDp = 4f).apply {
                setStroke(dp(1.5f), Palette.hudCream.toArgb())
            }
            contentDescription = "creatine"
        }
        creatineRow.addView(icon, LinearLayout.LayoutParams(dp(18f), dp(26f)).apply { rightMargin = dp(8f) })
        creatineLabel = arcade(22f, Palette.accentGold, "0", outlineDp = 2f)
        creatineRow.addView(creatineLabel)
        creatineCard = creatineRow
        root.addView(creatineCard, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(32f); rightMargin = dp(16f)
        })

        rivalStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        root.addView(rivalStack, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(88f); rightMargin = dp(16f)
        })

        bannerLabel = arcade(28f, Palette.accentGold, outlineDp = 4f, letterSpacing = 0.06f).apply { alpha = 0f }
        root.addView(bannerLabel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dp(156f) })

        comboLabel = arcade(20f, Palette.accentGold, outlineDp = 3f, letterSpacing = 0.06f).apply {
            alpha = 0f
            contentDescription = "combo"
        }
        root.addView(comboLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(112f); leftMargin = dp(18f)
        })

        toastLabel = arcade(26f, Palette.hotOrange, outlineDp = 4f, letterSpacing = 0.06f).apply { alpha = 0f }
        root.addView(toastLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { bottomMargin = dp(120f) })

        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = cardBackground(Palette.hudNavy, Palette.hudCream, radiusDp = 999f, alpha = 0.85f)
            elevation = dpf(6f)
            isClickable = true
            contentDescription = "pause"
            visibility = View.GONE
            setOnClickListener { game.setPaused(true) }
        }
        repeat(2) { i ->
            bars.addView(View(this).apply { background = pillBackground(Palette.hudCream.toArgb(), 2f) },
                LinearLayout.LayoutParams(dp(5f), dp(16f)).apply { if (i == 1) leftMargin = dp(5f) })
        }
        pauseButton = bars
        root.addView(pauseButton, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(34f)
        })

        flashView = View(this).apply { alpha = 0f; isClickable = false }
        root.addView(flashView, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun setupPause(root: FrameLayout) {
        pauseOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(128, 0, 0, 0))
            visibility = View.GONE
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground(Palette.hudInk, Palette.hotOrange, radiusDp = 26f, alpha = 0.94f)
            setPadding(dp(34f), dp(26f), dp(34f), dp(26f))
            elevation = dpf(10f)
        }
        val title = arcade(44f, Palette.hotOrange, "PAUSED", outlineDp = 5f, letterSpacing = 0.08f)
        soundButton = arcade(15f, Palette.accentGold, outlineDp = 0f, letterSpacing = 0.15f).apply {
            setShadowLayer(0f, 0f, 0f, 0)
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
            isClickable = true
            contentDescription = "soundToggle"
            setOnClickListener {
                sound.enabled = !sound.enabled
                refreshSoundButton()
                pop(this, 1.12f)
                haptic(Haptic.LIGHT)
            }
        }
        refreshSoundButton()
        val resume = arcade(20f, Palette.hudCream, "TAP TO RESUME", outlineDp = 3f, letterSpacing = 0.12f)
        card.addView(title, wrap())
        card.addView(soundButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(20f) })
        card.addView(resume, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(22f) })
        pauseOverlay.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { bottomMargin = dp(60f) })
        root.addView(pauseOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        pulse(resume)
    }

    private fun refreshSoundButton() {
        val on = sound.enabled
        val color = if (on) Palette.accentGold else Palette.hudSilver
        soundButton.text = if (on) "♪  SOUND ON" else "♪  SOUND OFF"
        soundButton.setFill(color.toArgb())
        soundButton.background = pillBackground(Palette.hudNavy.toArgb()).apply { setStroke(dp(2f), color.toArgb()) }
    }

    private fun haptic(kind: Haptic) {
        val constant = when (kind) {
            Haptic.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
            Haptic.MEDIUM -> HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.HEAVY -> HapticFeedbackConstants.LONG_PRESS
            Haptic.SUCCESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
        }
        rootView.performHapticFeedback(constant)
    }

    // MARK: - Title / game over

    private fun setupOverlays(root: FrameLayout) {
        titleOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val ribbon = caption("★  ARCADE EDITION  ★", Palette.accentGold).apply { textSize = 13f }
        val logoTop = arcade(74f, Palette.hudCream, "JUMPY", outlineDp = 6f, letterSpacing = 0.02f).apply {
            setShadowLayer(3f, 0f, 6f, Color.argb(170, 0, 0, 0))
        }
        val logoBottom = arcade(74f, Palette.hotOrange, "OTTER", outlineDp = 6f, letterSpacing = 0.02f).apply {
            setShadowLayer(3f, 0f, 6f, Color.argb(170, 0, 0, 0))
        }
        hiScoreLabel = arcade(20f, Palette.accentGold, "HI-SCORE  0", outlineDp = 3f, letterSpacing = 0.1f)
        lifetimeLabel = arcade(14f, Palette.hudSilver, "GAMES 0  ·  HOPS 0", outlineDp = 2.5f, letterSpacing = 0.08f)
        val tap = arcade(24f, Palette.hudCream, "TAP TO HOP", outlineDp = 3f, letterSpacing = 0.12f)
        val steer = arcade(14f, Palette.hudCream, "SWIPE TO STEER", outlineDp = 2.5f, letterSpacing = 0.2f)
        val credits = arcade(12f, Palette.hudCream, "1UP  ·  CREDITS 99  ·  PRESS TO START", outlineDp = 2.5f, letterSpacing = 0.18f)

        logoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        logoColumn.addView(logoTop, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6f) })
        logoColumn.addView(logoBottom, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = -dp(14f) })
        logoColumn.addView(hiScoreLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14f) })
        logoColumn.addView(lifetimeLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4f) })

        // attract swap: logo <-> high-score table
        boardCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground(Palette.hudNavy, Palette.accentGold, radiusDp = 22f, alpha = 0.9f)
            setPadding(dp(26f), dp(14f), dp(26f), dp(14f))
            elevation = dpf(8f)
            alpha = 0f
            contentDescription = "leaderboard"
        }
        boardCard.addView(arcade(24f, Palette.accentGold, "HIGH SCORES", outlineDp = 4f, letterSpacing = 0.08f), wrap())
        repeat(K.leaderboardSize) { i ->
            val row = arcade(20f, Palette.hudCream, outlineDp = 3f, letterSpacing = 0.1f)
            boardRows.add(row)
            boardCard.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(if (i == 0) 8f else 3f) })
        }
        val attract = FrameLayout(this)
        attract.addView(logoColumn, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        attract.addView(boardCard, FrameLayout.LayoutParams(dp(250f), WRAP, Gravity.CENTER))

        skinLabel = arcade(22f, Palette.otter, outlineDp = 3f, letterSpacing = 0.1f).apply { contentDescription = "skinName" }
        skinHint = arcade(10f, Palette.hudSilver, outlineDp = 0f, letterSpacing = 0.12f).apply { setShadowLayer(0f, 0f, 0f, 0) }
        skinCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(9f))
            elevation = dpf(6f)
            contentDescription = "skinCard"
        }
        skinCard.addView(skinLabel, wrap())
        skinCard.addView(skinHint, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2f) })

        titleOverlay.addView(ribbon, wrap())
        titleOverlay.addView(attract, wrap())
        titleOverlay.addView(skinCard, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(28f) })
        titleOverlay.addView(tap, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(22f) })
        titleOverlay.addView(steer, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6f) })
        titleOverlay.addView(credits, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(40f) })
        root.addView(titleOverlay, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dp(96f) })
        pulse(tap)
        bob(logoTop, 6f, 1400L)
        bob(logoBottom, 6f, 1700L)

        gameOverPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground(Palette.hudInk, Palette.hotOrange, radiusDp = 26f, alpha = 0.94f)
            setPadding(dp(34f), dp(24f), dp(34f), dp(24f))
            elevation = dpf(10f)
            visibility = View.GONE
            contentDescription = "gameOverPanel"
        }
        val title = arcade(40f, Palette.dangerRed, "GAME OVER", outlineDp = 5f, letterSpacing = 0.04f)
        rankPill = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.2f
            setTextColor(Palette.hudInk.toArgb())
            setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
            background = pillBackground(Palette.hudSilver.toArgb())
            contentDescription = "rank"
        }
        val scoreCaption = caption("SCORE")
        finalScoreLabel = arcade(56f, Palette.hudCream, "0", outlineDp = 4f).apply { contentDescription = "finalScore" }
        bestLabel = arcade(18f, Palette.accentGold, outlineDp = 2f, letterSpacing = 0.1f)
        val retry = arcade(20f, Palette.hudCream, "TAP TO RETRY", outlineDp = 3f, letterSpacing = 0.12f)
        placementLabel = arcade(15f, Palette.hudCream, outlineDp = 2f, letterSpacing = 0.12f).apply { contentDescription = "placement" }
        gameOverPanel.addView(title, wrap())
        gameOverPanel.addView(rankPill, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10f) })
        gameOverPanel.addView(scoreCaption, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16f) })
        gameOverPanel.addView(finalScoreLabel, wrap())
        gameOverPanel.addView(bestLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8f) })
        gameOverPanel.addView(placementLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6f) })
        gameOverPanel.addView(retry, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(22f) })
        root.addView(gameOverPanel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { bottomMargin = dp(60f) })
        pulse(retry)
    }

    // vertical LinearLayout defaults children to MATCH_PARENT width, so wide labels must opt into WRAP
    private fun wrap() = LinearLayout.LayoutParams(WRAP, WRAP)

    private fun pulse(v: View) {
        v.animate().alpha(0.35f).setDuration(700).withEndAction {
            v.animate().alpha(1f).setDuration(700).withEndAction { pulse(v) }.start()
        }.start()
    }

    private fun bob(v: View, amountDp: Float, duration: Long) {
        v.animate().translationY(-dpf(amountDp)).setDuration(duration).withEndAction {
            v.animate().translationY(0f).setDuration(duration).withEndAction { bob(v, amountDp, duration) }.start()
        }.start()
    }

    private fun pop(v: View, peak: Float = 1.18f) {
        v.animate().cancel()
        v.scaleX = 1f; v.scaleY = 1f
        v.animate().scaleX(peak).scaleY(peak).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }

    private fun fillBoard(board: List<Int>) {
        val ordinals = listOf("1ST", "2ND", "3RD", "4TH", "5TH")
        val colors = listOf(Palette.accentGold, Palette.hudSilver, Palette.hotOrange, Palette.hudCream, Palette.hudCream)
        boardRows.forEachIndexed { i, row ->
            row.text = "${ordinals[i]}   ${board.getOrNull(i)?.toString() ?: "---"}"
            row.setFill(colors[i].toArgb())
        }
    }

    private fun advanceAttract() {
        if (titleOverlay.visibility != View.VISIBLE) return
        showingBoard = !showingBoard
        logoColumn.animate().alpha(if (showingBoard) 0f else 1f).setDuration(350).start()
        boardCard.animate().cancel()
        if (showingBoard) {
            boardCard.scaleX = 0.85f; boardCard.scaleY = 0.85f
            boardCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(OvershootInterpolator(1.8f)).start()
        } else {
            boardCard.animate().alpha(0f).setDuration(350).start()
        }
    }

    private fun resetAttract() {
        showingBoard = false
        logoColumn.animate().cancel(); logoColumn.alpha = 1f
        boardCard.animate().cancel(); boardCard.alpha = 0f
    }

    private fun hidePauseChrome() {
        pauseButton.visibility = View.GONE
        pauseOverlay.visibility = View.GONE
    }

    private fun showTitle(best: Int, board: List<Int>, gamesPlayed: Int, totalHops: Int) {
        hiScoreLabel.text = "HI-SCORE  $best"
        lifetimeLabel.text = "GAMES $gamesPlayed  ·  HOPS $totalHops"
        fillBoard(board)
        resetAttract()
        hidePauseChrome()
        gameOverPanel.visibility = View.GONE
        scoreLabel.text = "0"
        titleOverlay.animate().cancel()
        titleOverlay.visibility = View.VISIBLE
        titleOverlay.alpha = 0f
        titleOverlay.scaleX = 0.9f
        titleOverlay.scaleY = 0.9f
        titleOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    // MARK: - Gestures

    private fun setupGestures(root: View) {
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < dp(24f) && abs(dy) < dp(24f)) return false
                // Screen-up maps to world forward; screen-right maps to Dir.RIGHT (world -X).
                if (abs(dx) > abs(dy)) {
                    game.handleSwipe(if (dx > 0) Dir.RIGHT else Dir.LEFT)
                } else {
                    game.handleSwipe(if (dy < 0) Dir.FORWARD else Dir.BACK)
                }
                return true
            }
        })
        root.setOnTouchListener { _, event -> gestures.onTouchEvent(event); true }
    }

    private fun onTap() {
        if (game.isPaused) {
            game.setPaused(false)
        } else if (game.state == GameController.State.GAME_OVER) {
            if (!canRetry) return
            countUp?.cancel()
            gameOverPanel.animate().cancel()
            gameOverPanel.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(160).withEndAction {
                gameOverPanel.visibility = View.GONE
            }.start()
            scoreLabel.text = "0"
            game.restart()
        } else {
            game.handleTap()
        }
    }

    // MARK: - GameHUD (called from the GL thread)

    override fun hudSetScore(score: Int) {
        runOnUiThread {
            scoreLabel.text = score.toString()
            if (score > 0) pop(scoreLabel, if (score % K.milestoneEvery == 0) 1.4f else 1.18f)
        }
    }

    override fun hudSetCreatine(creatine: Int) {
        runOnUiThread {
            val changed = creatineLabel.text.toString() != creatine.toString()
            creatineLabel.text = creatine.toString()
            if (changed) pop(creatineCard, 1.25f)
        }
    }

    override fun hudStarted() {
        runOnUiThread {
            gameOverPanel.visibility = View.GONE
            scoreLabel.text = "0"
            pauseButton.visibility = View.VISIBLE
            titleOverlay.animate().cancel()
            titleOverlay.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setDuration(240).withEndAction {
                if (game.state != GameController.State.TITLE) titleOverlay.visibility = View.GONE
            }.start()
        }
    }

    override fun hudShowTitle(best: Int, board: List<Int>, gamesPlayed: Int, totalHops: Int) {
        runOnUiThread {
            if (game.state != GameController.State.TITLE) return@runOnUiThread
            showTitle(best, board, gamesPlayed, totalHops)
        }
    }

    override fun hudSkin(skin: Skin, next: Skin?, total: Int) {
        runOnUiThread {
            skinLabel.text = "◀  ${skin.name}  ▶"
            skinLabel.setFill(skin.labelColor.toArgb())
            skinHint.text = if (next != null) "SWIPE ◀ ▶  ·  ${next.name} AT ${next.unlockAt} CREATINE"
                else "SWIPE ◀ ▶  ·  ALL OTTERS UNLOCKED"
            skinCard.background = cardBackground(Palette.hudNavy, skin.fur, radiusDp = 18f, alpha = 0.85f)
            pop(skinCard, 1.1f)
        }
    }

    override fun hudCombo(combo: Int) {
        runOnUiThread {
            if (combo >= K.comboShowAt) {
                comboLabel.text = "x$combo COMBO"
                comboLabel.setFill((if (combo >= K.comboBonusEvery) Palette.hotOrange else Palette.accentGold).toArgb())
                comboLabel.alpha = 1f
                pop(comboLabel, 1.3f)
            } else if (comboLabel.alpha > 0f) {
                comboLabel.animate().cancel()
                comboLabel.animate().alpha(0f).setDuration(250).start()
            }
        }
    }

    override fun hudToast(text: String, color: Rgb) {
        runOnUiThread {
            toastLabel.text = text
            toastLabel.setFill(color.toArgb())
            toastLabel.animate().cancel()
            toastLabel.alpha = 1f
            toastLabel.translationY = 0f
            toastLabel.scaleX = 0.5f; toastLabel.scaleY = 0.5f
            toastLabel.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.4f))
                .withEndAction {
                    toastLabel.animate().alpha(0f).translationY(-dpf(30f)).setStartDelay(450).setDuration(400).start()
                }.start()
        }
    }

    override fun hudPaused(paused: Boolean) {
        runOnUiThread {
            pauseOverlay.animate().cancel()
            if (paused) {
                refreshSoundButton()
                pauseOverlay.alpha = 0f
                pauseOverlay.visibility = View.VISIBLE
                pauseOverlay.animate().alpha(1f).setDuration(200).start()
                pauseOverlay.getChildAt(0)?.let { card ->
                    card.scaleX = 1.3f; card.scaleY = 1.3f
                    card.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(OvershootInterpolator(1.6f)).start()
                }
            } else {
                pauseOverlay.animate().alpha(0f).setDuration(180).withEndAction {
                    if (!game.isPaused) pauseOverlay.visibility = View.GONE
                }.start()
            }
        }
    }

    override fun hudHaptic(kind: Haptic) {
        runOnUiThread { haptic(kind) }
    }

    override fun hudGameOver(score: Int, best: Int, creatine: Int, newBest: Boolean, placement: Int?) {
        runOnUiThread {
            hidePauseChrome()
            if (placement != null) {
                placementLabel.text = "#$placement ON THE BOARD"
                placementLabel.visibility = View.VISIBLE
            } else {
                placementLabel.visibility = View.GONE
            }
            rankPill.text = Rank.title(score)
            rankPill.background = pillBackground(Rank.color(score).toArgb())
            if (newBest) {
                bestLabel.text = "★  NEW RECORD!  ★"
                bestLabel.setFill(Palette.accentGold.toArgb())
                pulse(bestLabel)
            } else {
                bestLabel.animate().cancel()
                bestLabel.alpha = 1f
                bestLabel.text = "BEST  $best"
                bestLabel.setFill(Palette.hudSilver.toArgb())
            }
            titleOverlay.animate().cancel()
            titleOverlay.visibility = View.GONE

            gameOverPanel.animate().cancel()
            gameOverPanel.alpha = 0f
            gameOverPanel.scaleX = 0.6f
            gameOverPanel.scaleY = 0.6f
            gameOverPanel.visibility = View.VISIBLE
            canRetry = false
            gameOverPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
            gameOverPanel.postDelayed({ canRetry = true }, 500)

            countUp?.cancel()
            finalScoreLabel.text = "0"
            if (score > 0) {
                countUp = ValueAnimator.ofInt(0, score).apply {
                    duration = (300L + 22L * score).coerceAtMost(1400L)
                    startDelay = 250
                    addUpdateListener { finalScoreLabel.text = (it.animatedValue as Int).toString() }
                    start()
                }
            }
        }
    }

    override fun hudFlash(color: Rgb, alpha: Float) {
        runOnUiThread {
            flashView.setBackgroundColor(color.toArgb())
            flashView.animate().cancel()
            flashView.alpha = alpha
            flashView.animate().alpha(0f).setDuration(380).start()
        }
    }

    override fun hudSetRivals(rivals: List<RivalStatus>) {
        runOnUiThread {
            val ids = rivals.map { it.id }.toSet()
            for ((id, chip) in rivalChips.toMap()) {
                if (id !in ids) {
                    rivalStack.removeView(chip.first)
                    rivalChips.remove(id)
                }
            }
            for (rival in rivals) {
                val (chip, label) = rivalChips.getOrPut(rival.id) {
                    val color = Palette.rivalColor(rival.id)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = cardBackground(Palette.hudNavy, color, radiusDp = 999f, alpha = 0.88f)
                        setPadding(dp(10f), dp(3f), dp(12f), dp(3f))
                    }
                    val dot = View(this).apply { background = pillBackground(color.toArgb()) }
                    row.addView(dot, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { rightMargin = dp(7f) })
                    val text = arcade(15f, Palette.hudCream, outlineDp = 1.5f, letterSpacing = 0.05f)
                    row.addView(text)
                    rivalStack.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(5f) })
                    row to text
                }
                label.text = "P${rival.id + 1}  ${rival.score}" + if (rival.alive) "" else "  ✕"
                chip.alpha = if (rival.alive) 1f else 0.45f
            }
        }
    }

    override fun hudBanner(text: String, color: Rgb) {
        runOnUiThread {
            bannerLabel.text = text
            bannerLabel.setFill(color.toArgb())
            bannerLabel.animate().cancel()
            bannerLabel.alpha = 1f
            bannerLabel.scaleX = 0.7f
            bannerLabel.scaleY = 0.7f
            bannerLabel.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f))
                .withEndAction {
                    bannerLabel.animate().alpha(0f).setStartDelay(1500).setDuration(450).start()
                }.start()
        }
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
