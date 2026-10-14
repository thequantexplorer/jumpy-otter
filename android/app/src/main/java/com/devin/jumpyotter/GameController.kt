package com.devin.jumpyotter

import android.content.SharedPreferences
import com.devin.jumpyotter.engine.FadeOut
import com.devin.jumpyotter.engine.Group
import com.devin.jumpyotter.engine.MoveBy
import com.devin.jumpyotter.engine.MoveTo
import com.devin.jumpyotter.engine.Node
import com.devin.jumpyotter.engine.RemoveFromParent
import com.devin.jumpyotter.engine.RotateBy
import com.devin.jumpyotter.engine.RotateTo
import com.devin.jumpyotter.engine.Run
import com.devin.jumpyotter.engine.ScaleTo
import com.devin.jumpyotter.engine.SceneRenderer
import com.devin.jumpyotter.engine.Sequence
import com.devin.jumpyotter.engine.Timing
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

interface GameHUD {
    fun hudSetScore(score: Int)
    fun hudSetCreatine(creatine: Int)
    /** [placement] is the 1-based leaderboard slot this run earned, if any. */
    fun hudGameOver(score: Int, best: Int, creatine: Int, newBest: Boolean, placement: Int?)
    fun hudStarted()
    fun hudShowTitle(best: Int, board: List<Int>, gamesPlayed: Int, totalHops: Int)
    /** Selected skin plus the next locked one (null once everything is unlocked). */
    fun hudSkin(skin: Skin, next: Skin?, total: Int)
    fun hudCombo(combo: Int)
    /** Small pop-up callout near the player (close calls, combo bonuses). */
    fun hudToast(text: String, color: Rgb)
    fun hudPaused(paused: Boolean)
    fun hudHaptic(kind: Haptic)
    fun hudSetRivals(rivals: List<RivalStatus>)
    fun hudBanner(text: String, color: Rgb)
    /** Full-screen colour flash (deaths, records); alpha in 0..1. */
    fun hudFlash(color: Rgb, alpha: Float)
}

data class RivalStatus(val id: Int, val score: Int, val alive: Boolean)

/**
 * Game logic + scene. Everything here runs on the GL thread; input arrives from the
 * UI thread through a small locked queue, exactly like the iOS controller.
 */
class GameController(
    private val prefs: SharedPreferences,
    private val sound: SoundManager,
    private val autopilot: Boolean,
    relayURL: String,
) {
    enum class State { TITLE, PLAYING, DYING, GAME_OVER }

    val renderer = SceneRenderer()
    var hud: GameHUD? = null
        set(value) {
            field = value
            value?.hudSetCreatine(totalCreatine)
            value?.hudSkin(Skins.all[skinIndex], Skins.next(totalCreatine), totalCreatine)
        }

    var state = State.TITLE
        private set

    @Volatile var isPaused = false
        private set

    // world
    private val worldNode = Node()
    private val terrain: TerrainGenerator

    // camera
    private val cameraRig = Node()
    private var camFrontier = 0f

    // player
    private var playerNode = Node()
    private val playerShadow = VoxelFactory.box(0.66f, 0.02f, 0.66f, Palette.shadow).also { it.opacity = 0.28f }
    private var playerRow = 0
    private var playerX = 0f
    private var isHopping = false
    private var ridingLog: MovingObject? = null
    private var ridingRow: Row? = null

    // input
    private val inputLock = Any()
    private val inputQueue = ArrayList<Dir>()
    private var restartRequested = false
    private var pendingSkinStep = 0
    private var pendingPause: Boolean? = null

    // scoring
    private var score = 0
    private var creatineCollected = 0
    private var totalCreatine = prefs.getInt("totalCreatine", 0)
    private var best = prefs.getInt("best", 0)
    private var gamesPlayed = prefs.getInt("gamesPlayed", 0)
    private var totalHops = prefs.getInt("totalHops", 0)
    private var board: List<Int> = prefs.getString("top5", null)
        ?.split(',')?.mapNotNull { it.toIntOrNull() }
        ?: if (best > 0) listOf(best) else emptyList()
    private var skinIndex = prefs.getInt("skin", 0).let { if (Skins.isUnlocked(it, totalCreatine)) it else 0 }

    // combo
    private var clock = 0f
    private var combo = 0
    private var lastForwardLand = -10f

    // timing
    private var lastTime = -1L
    private var idleTime = 0f
    private var eagleTriggered = false
    private var autoTimer = 1.0f
    private var eagleNode: Node? = null

    // deferred callbacks (replacement for DispatchQueue.asyncAfter), run on the GL thread
    private class Delayed(var remaining: Float, val block: () -> Unit)
    private val delayed = ArrayList<Delayed>()

    // multiplayer
    private val mp = MultiplayerClient(relayURL)
    private class Peer {
        var score = 0
        var alive = true
        var ghost: Node? = null
    }
    private val peers = HashMap<Int, Peer>()
    private var hazardBoostTimer = 0f
    private var stateSendTimer = 0f
    private var lastSentRow = Int.MIN_VALUE
    private var lastSentX = 0f
    private var announcedWin = false
    private var announcedRecord = false

    // GL resources scheduled for deletion (nodes removed from the graph)
    private val garbageNodes = ArrayList<Node>()

    init {
        renderer.background = Palette.sky
        renderer.root.addChild(worldNode)
        renderer.root.addChild(cameraRig)
        terrain = TerrainGenerator(worldNode) { row -> garbageNodes.add(row.node) }
        setupCamera()
        worldNode.addChild(playerShadow)
        startFresh()
        mp.connect()
    }

    private fun setupCamera() {
        // giant backdrop under the world so screen corners never show sky
        val backdrop = VoxelFactory.box(140f, 0.1f, 140f, Palette.grassSide, 0f, -0.62f, 0f)
        cameraRig.addChild(backdrop)
    }

    private fun startFresh() {
        terrain.reset()
        terrain.ensure(-K.rowsBehindKeep, K.rowsAhead)

        eagleNode?.let { it.removeFromParent(); garbageNodes.add(it) }
        eagleNode = null
        playerNode.removeFromParent()
        garbageNodes.add(playerNode)
        playerNode = VoxelFactory.wiskers(Skins.all[skinIndex])
        playerNode.position.set(0f, 0f, 0f)
        isPaused = false
        combo = 0
        lastForwardLand = -10f
        hud?.hudCombo(0)
        worldNode.addChild(playerNode)

        playerRow = 0
        playerX = 0f
        isHopping = false
        ridingLog = null
        ridingRow = null
        score = 0
        creatineCollected = 0
        idleTime = 0f
        eagleTriggered = false
        camFrontier = 0f
        cameraRig.position.set(0f, 0f, 1.5f)
        cameraRig.removeAllActions()
        delayed.clear()

        synchronized(inputLock) { inputQueue.clear() }

        announcedWin = false
        announcedRecord = false
        playerShadow.hidden = false
        lastSentRow = Int.MIN_VALUE
        mp.sendState(0, 0f, 0, true)

        hud?.hudSetScore(0)
        hud?.hudSetCreatine(totalCreatine)
    }

    // MARK: - Public controls (UI thread)

    fun handleTap() {
        when (state) {
            State.TITLE -> {
                state = State.PLAYING
                hud?.hudStarted()
                enqueue(Dir.FORWARD)
            }
            State.PLAYING -> enqueue(Dir.FORWARD)
            State.GAME_OVER -> restart()
            State.DYING -> {}
        }
    }

    fun handleSwipe(dir: Dir) {
        if (isPaused || (state != State.PLAYING && state != State.TITLE)) return
        if (state == State.TITLE && (dir == Dir.LEFT || dir == Dir.RIGHT)) {
            synchronized(inputLock) { pendingSkinStep += if (dir == Dir.RIGHT) 1 else -1 }
            return
        }
        if (state == State.TITLE) {
            state = State.PLAYING
            hud?.hudStarted()
        }
        enqueue(dir)
    }

    /** Requests pause/resume; only takes effect while a run is in progress. */
    fun setPaused(paused: Boolean) {
        synchronized(inputLock) { pendingPause = paused }
    }

    fun presentTitle() {
        hud?.hudShowTitle(best, board, gamesPlayed, totalHops)
    }

    fun restart() {
        synchronized(inputLock) {
            restartRequested = true
            inputQueue.clear()
        }
    }

    private fun applyPendingRestart() {
        val shouldRestart = synchronized(inputLock) {
            val r = restartRequested
            restartRequested = false
            r
        }
        if (!shouldRestart) return
        state = State.TITLE
        startFresh()
        hud?.hudShowTitle(best, board, gamesPlayed, totalHops)
    }

    private fun applyPendingPause() {
        val request = synchronized(inputLock) { pendingPause.also { pendingPause = null } } ?: return
        if (request == isPaused) return
        if (request && state != State.PLAYING) return
        isPaused = request
        hud?.hudPaused(request)
    }

    private fun applyPendingSkin() {
        val step = synchronized(inputLock) { pendingSkinStep.also { pendingSkinStep = 0 } }
        if (step == 0 || state != State.TITLE) return
        val count = Skins.all.size
        var idx = skinIndex
        do {
            idx = ((idx + Integer.signum(step)) % count + count) % count
        } while (!Skins.isUnlocked(idx, totalCreatine))
        if (idx == skinIndex) { bumpAnimation(); return }
        skinIndex = idx
        prefs.edit().putInt("skin", idx).apply()

        val old = playerNode
        val fresh = VoxelFactory.wiskers(Skins.all[idx])
        fresh.position.set(playerX, old.position.y, playerRow.toFloat())
        fresh.eulerAngles.set(old.eulerAngles.x, old.eulerAngles.y, old.eulerAngles.z)
        old.removeFromParent()
        garbageNodes.add(old)
        worldNode.addChild(fresh)
        playerNode = fresh
        fresh.runAction(
            Group(
                RotateBy((Math.PI * 2).toFloat(), 0.4f).also { it.timing = Timing.EASE_OUT },
                Sequence(MoveBy(0f, 0.6f, 0f, 0.2f), MoveBy(0f, -0.6f, 0f, 0.2f)),
            )
        )
        spawnBurst(playerX, 0.5f, playerRow.toFloat(), Skins.all[idx].fur, 10, 0.05f, 0.1f, 0.9f)
        sound.play(Sfx.COIN, 0.6f)
        hud?.hudHaptic(Haptic.LIGHT)
        hud?.hudSkin(Skins.all[idx], Skins.next(totalCreatine), totalCreatine)
    }

    private fun enqueue(dir: Dir) {
        synchronized(inputLock) {
            if (inputQueue.size < 2) inputQueue.add(dir)
        }
    }

    // MARK: - Frame loop (GL thread)

    fun onSurfaceCreated() {
        renderer.onSurfaceCreated()
        lastTime = -1
    }

    fun onSurfaceChanged(w: Int, h: Int) = renderer.onSurfaceChanged(w, h)

    fun onDrawFrame() {
        val now = System.nanoTime()
        if (lastTime < 0) lastTime = now
        val dt = minOf((now - lastTime) / 1_000_000_000f, 1f / 30f)
        lastTime = now

        applyPendingRestart()
        applyPendingPause()
        applyPendingSkin()
        if (isPaused) {
            renderer.target.set(cameraRig.position)
            renderer.eye.set(cameraRig.position.x - 6.5f, cameraRig.position.y + 10.5f, cameraRig.position.z - 7.0f)
            renderer.draw()
            return
        }
        clock += dt
        if (combo > 0 && clock - lastForwardLand > K.comboWindow + K.hopDuration) {
            combo = 0
            hud?.hudCombo(0)
        }
        processMultiplayer(dt)
        updateRows(dt)
        if (autopilot) runAutopilot(dt)
        if (state == State.PLAYING) {
            drainInput()
            updateRiding(dt)
            checkCollisions()
            updateCamera(dt)
            checkEagleConditions(dt)
        } else if (state == State.TITLE || state == State.DYING) {
            updateCamera(dt, follow = state == State.TITLE)
        }

        renderer.root.updateActions(dt)
        runDelayed(dt)
        updatePlayerShadow()

        renderer.target.set(cameraRig.position)
        renderer.eye.set(cameraRig.position.x - 6.5f, cameraRig.position.y + 10.5f, cameraRig.position.z - 7.0f)
        renderer.draw()

        for (n in garbageNodes) n.disposeMeshes(renderer.contextGen)
        garbageNodes.clear()
    }

    /** Soft contact shadow under the otter (the GLES2 renderer has no shadow maps). */
    private fun updatePlayerShadow() {
        if (playerNode.parent !== worldNode) { playerShadow.hidden = true; return }
        val p = playerNode.position
        val surface = terrain.rows[p.z.roundToInt()]?.surfaceY ?: 0f
        val height = (p.y - surface).coerceAtLeast(0f)
        val s = (1f - height * 0.9f).coerceIn(0.45f, 1f)
        playerShadow.position.set(p.x, surface + 0.012f, p.z)
        playerShadow.scale.set(s, 1f, s)
        playerShadow.opacity = 0.28f * s * playerNode.opacity
    }

    private fun after(seconds: Float, block: () -> Unit) {
        delayed.add(Delayed(seconds, block))
    }

    private fun runDelayed(dt: Float) {
        if (delayed.isEmpty()) return
        val snapshot = delayed.toList()
        for (d in snapshot) {
            d.remaining -= dt
            if (d.remaining <= 0f) {
                delayed.remove(d)
                d.block()
            }
        }
    }

    // MARK: - Rows / moving objects

    private fun updateRows(dt: Float) {
        val camRow = cameraRig.position.z.roundToInt()
        terrain.ensure(camRow - K.rowsBehindKeep, max(camRow, playerRow) + K.rowsAhead)

        val hazardBoost = if (hazardBoostTimer > 0) K.garbageSpeedBoost else 1f
        for (row in terrain.rows.values) {
            when (row.kind) {
                RowKind.ROAD, RowKind.RIVER -> {
                    val boost = if (row.kind == RowKind.ROAD) hazardBoost else 1f
                    for (obj in row.objects) {
                        obj.x += row.dir * row.speed * boost * dt
                        if (obj.x > K.wrapHalf) obj.x -= K.wrapHalf * 2
                        if (obj.x < -K.wrapHalf) obj.x += K.wrapHalf * 2
                        obj.node.position.x = obj.x
                    }
                }
                RowKind.RAIL -> updateTrain(row, dt)
                RowKind.GRASS -> {}
            }
        }
    }

    private fun updateTrain(row: Row, dt: Float) {
        val nearPlayer = abs(row.index - playerRow) < 18
        row.trainTimer -= dt
        when (row.trainPhase) {
            TrainPhase.IDLE -> {
                if (row.trainTimer <= 0 && nearPlayer) {
                    row.trainPhase = TrainPhase.WARNING
                    row.trainTimer = 2.4f
                    row.blinkTimer = 0f
                    if (abs(row.index - playerRow) < 14) sound.play(Sfx.BELL, 1.0f)
                }
            }
            TrainPhase.WARNING -> {
                row.blinkTimer -= dt
                if (row.blinkTimer <= 0) {
                    row.blinkTimer = 0.18f
                    row.blinkOn = !row.blinkOn
                    setSignal(row, row.blinkOn, !row.blinkOn)
                    if (row.blinkOn && abs(row.index - playerRow) < 14) sound.play(Sfx.BELL, 0.5f)
                }
                if (row.trainTimer <= 0) {
                    row.trainPhase = TrainPhase.RUNNING
                    val model = VoxelFactory.train(5)
                    if (row.dir < 0) model.node.eulerAngles.y = Math.PI.toFloat()
                    val startX = -row.dir * (K.wrapHalf + model.halfLen + 4)
                    model.node.position.set(startX, 0f, 0f)
                    row.node.addChild(model.node)
                    row.train = MovingObject(model.node, startX, model.halfLen)
                    if (abs(row.index - playerRow) < 10) sound.play(Sfx.TRAIN, 0.9f)
                }
            }
            TrainPhase.RUNNING -> {
                val train = row.train
                if (train == null) { row.trainPhase = TrainPhase.IDLE; return }
                train.x += row.dir * 16.0f * dt
                train.node.position.x = train.x
                if (abs(train.x) > K.wrapHalf + train.halfLen + 5) {
                    train.node.removeFromParent()
                    garbageNodes.add(train.node)
                    row.train = null
                    row.trainPhase = TrainPhase.IDLE
                    row.trainTimer = rand(2.5f, 7.0f)
                    setSignal(row, litA = false, litB = false)
                }
            }
        }
    }

    private fun setSignal(row: Row, litA: Boolean, litB: Boolean) {
        val signal = row.signal ?: return
        val on = floatArrayOf(0.9f, 0.1f, 0.05f)
        signal.childNamed("lightA")?.emissive = if (litA) on else null
        signal.childNamed("lightB")?.emissive = if (litB) on else null
    }

    // MARK: - Input / hopping

    private fun drainInput() {
        if (isHopping) return
        val dir = synchronized(inputLock) { if (inputQueue.isEmpty()) null else inputQueue.removeAt(0) } ?: return
        hop(dir)
    }

    private fun hop(dir: Dir) {
        idleTime = 0f
        playerNode.runAction(RotateTo(0f, dir.yaw, 0f, 0.05f, shortest = true))

        val targetRowIndex = playerRow + dir.dz
        val targetRow = terrain.rows[targetRowIndex] ?: run { bumpAnimation(); return }

        var targetX: Float
        if (ridingLog != null) {
            targetX = playerX + dir.dx
            if (targetRow.kind != RowKind.RIVER) targetX = targetX.roundToInt().toFloat()
        } else {
            targetX = (playerX + dir.dx).roundToInt().toFloat()
        }

        if (targetRow.kind != RowKind.RIVER) {
            val clamped = targetX.coerceIn(K.minCol.toFloat(), K.maxCol.toFloat())
            if (clamped != targetX && dir.dz == 0) { bumpAnimation(); return }
            targetX = clamped
            if (targetRow.blocked.contains(targetX.roundToInt())) { bumpAnimation(); return }
        } else if (dir.dz == 0 && abs(targetX) > K.maxCol + 1.2f) {
            bumpAnimation(); return
        }

        val current = terrain.rows[playerRow]
        if (dir.dz != 0 && current != null && current.kind == RowKind.ROAD) {
            val closeCall = current.objects.any {
                val gap = abs(it.presentationX - playerX) - it.halfLen - K.playerHalfWidth
                val approaching = (it.presentationX - playerX) * current.dir < 0
                approaching && gap < K.closeCallMargin
            }
            if (closeCall) {
                hud?.hudToast("CLOSE CALL!", Palette.hotOrange)
                hud?.hudHaptic(Haptic.MEDIUM)
            }
        }

        isHopping = true
        totalHops += 1
        ridingLog = null
        ridingRow = null
        sound.play(Sfx.HOP, 0.9f)
        hud?.hudHaptic(Haptic.LIGHT)

        val targetY = targetRow.surfaceY
        val move = MoveTo(targetX, targetY, targetRowIndex.toFloat(), K.hopDuration).also { it.timing = Timing.EASE_OUT }
        val up = MoveBy(0f, K.hopHeight, 0f, K.hopDuration / 2).also { it.timing = Timing.EASE_OUT }
        val down = MoveBy(0f, -K.hopHeight, 0f, K.hopDuration / 2).also { it.timing = Timing.EASE_IN }
        val squash = Sequence(ScaleTo(1.12f, K.hopDuration / 2), ScaleTo(1.0f, K.hopDuration / 2))
        playerNode.runAction(Group(move, Sequence(up, down), squash)) {
            landed(targetRowIndex, targetX)
        }
    }

    private fun landed(rowIndex: Int, x: Float) {
        isHopping = false
        playerRow = rowIndex
        playerX = x
        if (state != State.PLAYING) return
        val row = terrain.rows[rowIndex] ?: return

        if (rowIndex > score) {
            score = rowIndex
            hud?.hudSetScore(score)
            combo = if (clock - lastForwardLand <= K.comboWindow + K.hopDuration) combo + 1 else 1
            lastForwardLand = clock
            hud?.hudCombo(combo)
            if (combo % K.comboBonusEvery == 0) {
                awardCreatine(1)
                sound.play(Sfx.COIN, 0.7f)
                hud?.hudToast("x$combo COMBO  +1", Palette.accentGold)
                hud?.hudHaptic(Haptic.SUCCESS)
            }
            if (score % K.milestoneEvery == 0) hud?.hudBanner("$score ROWS!", Palette.accentGold)
            if (score > best && !announcedRecord && best > 0) {
                announcedRecord = true
                hud?.hudBanner("NEW RECORD!", Palette.accentGold)
                hud?.hudFlash(Palette.accentGold, 0.35f)
                hud?.hudHaptic(Haptic.SUCCESS)
            }
        }

        // landing feedback
        if (row.kind == RowKind.RIVER) {
            spawnBurst(x, 0.15f, rowIndex.toFloat(), Palette.waterFoam, 4, 0.05f, 0.09f, 0.6f)
        } else {
            spawnBurst(x, 0.05f, rowIndex.toFloat(), Palette.hudCream, 4, 0.05f, 0.09f, 0.6f)
        }

        val col = x.roundToInt()
        row.creatine.remove(col)?.let { bottle ->
            creatineCollected += 1
            awardCreatine(1)
            hud?.hudHaptic(Haptic.SUCCESS)
            sound.play(Sfx.COIN, 0.9f)
            mp.sendGarbage(1)
            bottle.removeAllActions()
            bottle.runAction(
                Sequence(
                    Group(MoveBy(0f, 1.2f, 0f, 0.25f), FadeOut(0.25f)),
                    Run { garbageNodes.add(bottle) },
                    RemoveFromParent(),
                )
            )
            spawnBurst(x, 0.6f, rowIndex.toFloat(), Palette.accentGold, 10, 0.05f, 0.11f, 1.0f)
        }

        sendStateIfChanged()

        if (row.kind == RowKind.RIVER) {
            val log = row.objects.firstOrNull { abs(it.presentationX - x) <= it.halfLen + 0.35f }
            if (log != null) {
                ridingLog = log
                ridingRow = row
            } else {
                drown()
            }
        }
    }

    private fun awardCreatine(amount: Int) {
        val before = totalCreatine
        totalCreatine += amount
        prefs.edit().putInt("totalCreatine", totalCreatine).apply()
        hud?.hudSetCreatine(totalCreatine)
        for (skin in Skins.all) {
            if (skin.unlockAt in (before + 1)..totalCreatine) {
                hud?.hudBanner("${skin.name} OTTER UNLOCKED!", skin.fur)
                hud?.hudFlash(skin.fur, 0.35f)
            }
        }
    }

    private fun bumpAnimation() {
        if (isHopping) return
        sound.play(Sfx.BUMP, 0.7f)
        playerNode.runAction(Sequence(ScaleTo(0.85f, 0.05f), ScaleTo(1.0f, 0.08f)))
    }

    // MARK: - Riding logs

    private fun updateRiding(dt: Float) {
        val row = ridingRow ?: return
        if (ridingLog == null || isHopping) return
        playerX += row.dir * row.speed * dt
        playerNode.position.x = playerX
        if (abs(playerX) > K.maxCol + 1.8f) triggerEagle()
    }

    // MARK: - Collisions

    private fun checkCollisions() {
        if (eagleTriggered) return
        val px = playerNode.position.x
        val pz = playerNode.position.z
        val pRowIdx = pz.roundToInt()
        for (idx in (pRowIdx - 1)..(pRowIdx + 1)) {
            val row = terrain.rows[idx] ?: continue
            val rowHalfDepth = if (row.kind == RowKind.RAIL) K.trainRowHalfDepth else 0.45f
            if (abs(pz - row.index) >= rowHalfDepth) continue
            if (row.kind == RowKind.ROAD) {
                for (obj in row.objects) {
                    if (abs(obj.presentationX - px) < obj.halfLen + K.playerHalfWidth) {
                        squashDeath()
                        return
                    }
                }
            } else if (row.kind == RowKind.RAIL) {
                val train = row.train ?: continue
                val collisionHalfLen = max(0f, train.halfLen - K.trainCollisionInset)
                if (abs(train.presentationX - px) < collisionHalfLen + K.trainPlayerHalfWidth) {
                    squashDeath()
                    return
                }
            }
        }
    }

    // MARK: - Camera

    private fun updateCamera(dt: Float, follow: Boolean = true) {
        if (!follow) return
        if (state == State.PLAYING) camFrontier += K.cameraAutoAdvance * dt
        camFrontier = max(camFrontier, playerRow.toFloat())
        val targetZ = camFrontier + 1.8f
        val targetX = playerX * 0.45f
        val k = 1f - exp(-dt * 3.2f)
        cameraRig.position.x += (targetX - cameraRig.position.x) * k
        cameraRig.position.z += (targetZ - cameraRig.position.z) * k
    }

    // MARK: - Eagle / idle

    private fun checkEagleConditions(dt: Float) {
        if (eagleTriggered) return
        idleTime += dt
        if (idleTime > K.idleEagleSeconds || playerNode.position.z < cameraRig.position.z - K.cameraBehindLimit) {
            triggerEagle()
        }
    }

    private fun triggerEagle() {
        if (eagleTriggered || state != State.PLAYING) return
        eagleTriggered = true
        state = State.DYING
        sound.play(Sfx.EAGLE, 1.0f)
        hud?.hudHaptic(Haptic.HEAVY)

        val px = playerNode.position.x
        val pz = playerNode.position.z
        val eagle = VoxelFactory.eagle()
        eagle.position.set(px, 5.5f, pz - 14f)
        worldNode.addChild(eagle)
        eagleNode = eagle

        val swoopIn = MoveTo(px, 0.55f, pz, 0.55f).also { it.timing = Timing.EASE_IN }
        val flyOff = MoveBy(0f, 4.5f, 26f, 1.1f).also { it.timing = Timing.EASE_OUT }
        playerNode.removeAllActions()
        val player = playerNode
        eagle.runAction(
            Sequence(
                swoopIn,
                Run {
                    player.removeFromParent()
                    player.position.set(0f, -0.55f, 0.1f)
                    eagle.addChild(player)
                },
                flyOff,
                Run { finishGameOver() },
            )
        )
    }

    // MARK: - Deaths

    private fun squashDeath() {
        if (state != State.PLAYING) return
        state = State.DYING
        sound.play(Sfx.HIT, 1.0f)
        playerNode.removeAllActions()
        isHopping = false
        val px = playerNode.position.x
        val pz = playerNode.position.z
        playerNode.scale.set(1.5f, 0.08f, 1.5f)
        playerNode.position.y = terrain.rows[pz.roundToInt()]?.surfaceY ?: 0f
        spawnBurst(px, 0.4f, pz, Skins.all[skinIndex].fur, 14)
        shakeCamera()
        hud?.hudHaptic(Haptic.HEAVY)
        hud?.hudFlash(Palette.dangerRed, 0.45f)
        after(0.8f) { finishGameOver() }
    }

    private fun drown() {
        if (state != State.PLAYING) return
        state = State.DYING
        sound.play(Sfx.SPLASH, 1.0f)
        isHopping = false
        spawnBurst(playerX, 0.2f, playerRow.toFloat(), Palette.waterFoam, 16)
        hud?.hudFlash(Palette.water, 0.4f)
        hud?.hudHaptic(Haptic.HEAVY)
        val sink = Group(
            MoveBy(0f, -1.2f, 0f, 0.45f).also { it.timing = Timing.EASE_IN },
            FadeOut(0.45f).also { it.timing = Timing.EASE_IN },
        )
        // landed() runs inside the hop action's completion; defer a frame so the
        // new action is not dropped by the same evaluation pass
        after(0f) {
            playerNode.removeAllActions()
            playerNode.runAction(sink)
        }
        after(0.9f) { finishGameOver() }
    }

    private fun finishGameOver() {
        if (state != State.DYING) return
        state = State.GAME_OVER
        gamesPlayed += 1
        prefs.edit().putInt("gamesPlayed", gamesPlayed).putInt("totalHops", totalHops).apply()
        val newBest = score > best
        if (newBest) {
            best = score
            prefs.edit().putInt("best", best).apply()
        }
        var placement: Int? = null
        if (score > 0) {
            board = (board + score).sortedDescending().take(K.leaderboardSize)
            placement = board.indexOf(score).takeIf { it >= 0 }?.plus(1)
            prefs.edit().putString("top5", board.joinToString(",")).apply()
        }
        combo = 0
        hud?.hudCombo(0)
        hud?.hudGameOver(score, best, totalCreatine, newBest, placement)
        mp.sendGameOver(score)
        mp.sendState(playerRow, playerX, score, false)
    }

    // MARK: - Multiplayer

    private fun processMultiplayer(dt: Float) {
        if (hazardBoostTimer > 0) hazardBoostTimer -= dt

        var rosterChanged = false
        while (true) {
            val event = mp.poll() ?: break
            when (event) {
                is MultiplayerEvent.Connected -> {
                    for (id in event.peers) addPeer(id)
                    rosterChanged = true
                }
                is MultiplayerEvent.PeerJoined -> {
                    addPeer(event.id)
                    hud?.hudBanner("PLAYER ${event.id + 1} JOINED", Palette.rivalColor(event.id))
                    rosterChanged = true
                }
                is MultiplayerEvent.PeerState -> {
                    val peer = peers[event.id] ?: continue
                    peer.score = max(peer.score, event.score)
                    peer.alive = event.alive
                    updateGhost(peer, event.id, event.row, event.x, event.alive)
                    rosterChanged = true
                }
                is MultiplayerEvent.PeerGarbage -> {
                    if (state != State.PLAYING) continue
                    hazardBoostTimer += K.garbageBoostSeconds * event.amount
                    hud?.hudBanner("PLAYER ${event.id + 1} SENT TRAFFIC!", Palette.rivalColor(event.id))
                }
                is MultiplayerEvent.PeerGameOver -> {
                    val peer = peers[event.id] ?: continue
                    peer.score = max(peer.score, event.score)
                    peer.alive = false
                    updateGhost(peer, event.id, null, null, false)
                    rosterChanged = true
                    checkLastOtterStanding()
                }
                is MultiplayerEvent.OpponentLeft -> {
                    peers.remove(event.id)?.ghost?.let { it.removeFromParent(); garbageNodes.add(it) }
                    hud?.hudBanner("PLAYER ${event.id + 1} LEFT", Palette.rivalColor(event.id))
                    rosterChanged = true
                    checkLastOtterStanding()
                }
                MultiplayerEvent.Disconnected -> {
                    for (peer in peers.values) peer.ghost?.let { it.removeFromParent(); garbageNodes.add(it) }
                    peers.clear()
                    rosterChanged = true
                }
            }
        }
        if (rosterChanged) pushRivalHUD()

        if (state == State.PLAYING && ridingLog != null) {
            stateSendTimer -= dt
            if (stateSendTimer <= 0) {
                stateSendTimer = 0.15f
                sendStateIfChanged()
            }
        }
    }

    private fun addPeer(id: Int) {
        if (!peers.containsKey(id)) peers[id] = Peer()
    }

    private fun pushRivalHUD() {
        val rivals = peers.keys.sorted().map { RivalStatus(it, peers[it]!!.score, peers[it]!!.alive) }
        hud?.hudSetRivals(rivals)
    }

    private fun sendStateIfChanged() {
        if (!mp.connected) return
        if (lastSentRow == playerRow && abs(lastSentX - playerX) < 0.05f) return
        lastSentRow = playerRow
        lastSentX = playerX
        mp.sendState(playerRow, playerX, score, state == State.PLAYING || state == State.TITLE)
    }

    private fun updateGhost(peer: Peer, id: Int, row: Int?, x: Float?, alive: Boolean) {
        if (!alive) {
            val ghost = peer.ghost ?: return
            peer.ghost = null
            ghost.removeAllActions()
            ghost.runAction(
                Sequence(
                    Group(ScaleTo(0.05f, 0.3f), FadeOut(0.3f)),
                    Run { garbageNodes.add(ghost) },
                    RemoveFromParent(),
                )
            )
            return
        }
        if (row == null || x == null) return
        val ghost = peer.ghost ?: run {
            val g = VoxelFactory.wiskers()
            g.opacity = 0.55f
            g.addChild(VoxelFactory.box(0.28f, 0.14f, 0.28f, Palette.rivalColor(id), 0f, 1.15f, 0f))
            worldNode.addChild(g)
            peer.ghost = g
            g
        }
        val y = terrain.rows[row]?.surfaceY ?: 0f
        ghost.removeAllActions()
        ghost.runAction(MoveTo(x, y, row.toFloat(), 0.12f).also { it.timing = Timing.EASE_OUT })
    }

    private fun checkLastOtterStanding() {
        if (announcedWin || state != State.PLAYING || peers.isEmpty()) return
        if (peers.values.any { it.alive }) return
        announcedWin = true
        hud?.hudBanner("LAST OTTER STANDING — YOU WIN!", Palette.accentGold)
    }

    // MARK: - Autopilot (debug)

    private fun runAutopilot(dt: Float) {
        autoTimer -= dt
        if (autoTimer > 0) return
        when (state) {
            State.TITLE -> { handleTap(); autoTimer = 0.5f }
            State.PLAYING -> {
                if (!isHopping) chooseAutoMove()?.let { enqueue(it) }
                autoTimer = rand(0.25f, 0.45f)
            }
            State.GAME_OVER -> { restart(); autoTimer = 1.0f }
            State.DYING -> autoTimer = 0.5f
        }
    }

    private fun autoSafe(dir: Dir): Boolean {
        val row = terrain.rows[playerRow + dir.dz] ?: return false
        val targetX = if (ridingLog != null && row.kind == RowKind.RIVER) playerX + dir.dx
        else (playerX + dir.dx).roundToInt().toFloat()
        return when (row.kind) {
            RowKind.GRASS -> abs(targetX) <= K.maxCol && !row.blocked.contains(targetX.roundToInt())
            RowKind.ROAD -> {
                if (abs(targetX) > K.maxCol) return false
                row.objects.none {
                    val x = it.presentationX
                    val future = x + row.dir * row.speed * 0.55f
                    abs(x - targetX) < it.halfLen + 1.0f || abs(future - targetX) < it.halfLen + 1.0f
                }
            }
            RowKind.RAIL -> abs(targetX) <= K.maxCol && row.trainPhase == TrainPhase.IDLE
            RowKind.RIVER -> row.objects.any { abs(it.presentationX - targetX) <= it.halfLen + 0.1f }
        }
    }

    private fun chooseAutoMove(): Dir? {
        if (autoSafe(Dir.FORWARD)) return Dir.FORWARD
        val side = listOf(Dir.LEFT, Dir.RIGHT).shuffled().firstOrNull { autoSafe(it) }
        if (side != null && rand01() < 0.5f) return side
        return null
    }

    // MARK: - Effects

    private fun spawnBurst(
        x: Float, y: Float, z: Float, color: Rgb, count: Int,
        minSize: Float = 0.06f, maxSize: Float = 0.14f, spread: Float = 1.2f,
    ) {
        for (i in 0 until count) {
            val s = rand(minSize, maxSize)
            val piece = VoxelFactory.box(s, s, s, color)
            piece.position.set(x, y, z)
            worldNode.addChild(piece)
            val vx = rand(-spread, spread)
            val vz = rand(-spread, spread)
            val up = MoveBy(vx * 0.4f, rand(0.5f, 1.1f), vz * 0.4f, 0.18f).also { it.timing = Timing.EASE_OUT }
            val down = MoveBy(vx * 0.6f, -rand(0.7f, 1.3f), vz * 0.6f, 0.3f).also { it.timing = Timing.EASE_IN }
            piece.runAction(Sequence(up, down, FadeOut(0.12f), Run { garbageNodes.add(piece) }, RemoveFromParent()))
        }
    }

    private fun shakeCamera() {
        val amt = 0.18f
        cameraRig.runAction(
            Sequence(
                MoveBy(amt, 0f, -amt, 0.04f),
                MoveBy(-amt * 2, 0f, amt * 2, 0.08f),
                MoveBy(amt, 0f, -amt, 0.04f),
            )
        )
    }

    fun destroy() {
        mp.disconnect()
    }
}
