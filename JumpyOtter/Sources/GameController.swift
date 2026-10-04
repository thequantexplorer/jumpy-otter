import SceneKit
import UIKit

protocol GameHUD: AnyObject {
    func hudSetScore(_ score: Int)
    func hudSetCreatine(_ creatine: Int)
    /// `placement` is the 1-based leaderboard slot this run earned, if any.
    /// `duration` is unpaused play time from the first hop to death.
    func hudGameOver(score: Int, best: Int, creatine: Int, newBest: Bool, placement: Int?, duration: TimeInterval)
    func hudStarted()
    func hudShowTitle(best: Int, board: [Int])
    /// Selected skin plus the next locked one (nil once everything is unlocked).
    func hudSkin(_ skin: Skin, next: Skin?, total: Int)
    func hudCombo(_ combo: Int)
    /// Small pop-up callout near the player (close calls, combo bonuses).
    func hudToast(_ text: String, color: UIColor)
    func hudPaused(_ paused: Bool)
    func hudHaptic(_ kind: Haptic)
    func hudSetRivals(_ rivals: [RivalStatus])
    func hudBanner(_ text: String, color: UIColor)
    /// Full-screen colour flash (deaths, records).
    func hudFlash(_ color: UIColor)
}

struct RivalStatus {
    let id: Int
    let score: Int
    let alive: Bool
}

final class GameController: NSObject, SCNSceneRendererDelegate {

    enum State {
        case title, playing, dying, gameOver
    }

    let scene = SCNScene()
    weak var hud: GameHUD? {
        didSet {
            hud?.hudSetCreatine(totalCreatine)
            hud?.hudSkin(Skins.all[skinIndex], next: Skins.next(after: totalCreatine), total: totalCreatine)
        }
    }

    private(set) var state: State = .title {
        didSet {
            if state == .playing, oldValue == .title { runStartClock = clock }
            if state == .dying { runDuration = TimeInterval(clock - runStartClock) }
        }
    }
    private(set) var isPaused = false

    // world
    private let worldNode = SCNNode()
    private var terrain: TerrainGenerator!

    // camera
    private let cameraRig = SCNNode()
    private let cameraNode = SCNNode()
    private let lightNode = SCNNode()
    private var camFrontier: Float = 0  // furthest forward the camera has pushed

    // player
    private var playerNode = SCNNode()
    private var playerRow = 0
    private var playerX: Float = 0
    private var isHopping = false
    private var ridingLog: MovingObject?
    private var ridingRow: Row?
    private var facing: Dir = .forward

    // input
    private let inputLock = NSLock()
    private var inputQueue: [Dir] = []
    private var restartRequested = false
    private var pendingSkinStep = 0
    private var pendingPause: Bool?

    // scoring
    private var score = 0
    private var creatineCollected = 0
    private var totalCreatine: Int = {
        let defaults = UserDefaults.standard
        if defaults.object(forKey: "totalCreatine") != nil {
            return defaults.integer(forKey: "totalCreatine")
        }
        return defaults.integer(forKey: "totalCoins")
    }()
    private var best = UserDefaults.standard.integer(forKey: "best")
    private lazy var board: [Int] = {
        if let saved = UserDefaults.standard.array(forKey: "top5") as? [Int] { return saved }
        return best > 0 ? [best] : []
    }()
    private lazy var skinIndex: Int = {
        let saved = UserDefaults.standard.integer(forKey: "skin")
        return Skins.isUnlocked(saved, total: totalCreatine) ? saved : 0
    }()

    // combo
    private var clock: Float = 0
    private var combo = 0
    private var lastForwardLand: Float = -10

    // timing
    private var lastTime: TimeInterval = -1
    private var runStartClock: Float = 0
    private var runDuration: TimeInterval = 0
    private var idleTime: Float = 0
    private var eagleTriggered = false

    // autopilot (debug self-play via "AUTOPILOT" launch argument)
    private let autopilot = ProcessInfo.processInfo.arguments.contains("AUTOPILOT")
    private var autoTimer: Float = 1.0

    private var eagleNode: SCNNode?

    // multiplayer
    private let mp = MultiplayerClient()
    private final class Peer {
        var score = 0
        var alive = true
        var ghost: SCNNode?
    }
    private var peers: [Int: Peer] = [:]
    private var hazardBoostTimer: Float = 0
    private var stateSendTimer: Float = 0
    private var lastSentState: (row: Int, x: Float)?
    private var announcedWin = false
    private var announcedRecord = false

    // MARK: - Setup

    override init() {
        super.init()
        scene.background.contents = Palette.sky
        scene.rootNode.addChildNode(worldNode)
        terrain = TerrainGenerator(parent: worldNode)
        setupCamera()
        setupLights()
        startFresh()
        mp.connect()
    }

    private func setupCamera() {
        let camera = SCNCamera()
        camera.usesOrthographicProjection = true
        camera.orthographicScale = 7.5
        camera.zNear = 0.1
        camera.zFar = 100
        cameraNode.camera = camera
        cameraNode.position = SCNVector3(-6.5, 10.5, -7.0)
        scene.rootNode.addChildNode(cameraRig)
        cameraRig.addChildNode(cameraNode)
        let constraint = SCNLookAtConstraint(target: cameraRig)
        constraint.isGimbalLockEnabled = true
        cameraNode.constraints = [constraint]

        // giant backdrop under the world so screen corners never show sky
        let backdrop = VoxelFactory.box(w: 140, h: 0.1, l: 140,
                                        color: Palette.grassSide, y: -0.62, chamfer: 0)
        backdrop.castsShadow = false
        cameraRig.addChildNode(backdrop)
    }

    private func setupLights() {
        let ambient = SCNLight()
        ambient.type = .ambient
        ambient.intensity = 560
        ambient.color = UIColor(red: 0.90, green: 0.95, blue: 1.0, alpha: 1)
        let ambientNode = SCNNode()
        ambientNode.light = ambient
        scene.rootNode.addChildNode(ambientNode)

        let sun = SCNLight()
        sun.type = .directional
        sun.intensity = 780
        sun.color = UIColor(red: 1.0, green: 0.96, blue: 0.88, alpha: 1)
        sun.castsShadow = true
        sun.shadowMapSize = CGSize(width: 2048, height: 2048)
        sun.shadowRadius = 4
        sun.shadowSampleCount = 8
        sun.shadowColor = UIColor(white: 0, alpha: 0.32)
        sun.automaticallyAdjustsShadowProjection = true
        lightNode.light = sun
        lightNode.eulerAngles = SCNVector3(-Float.pi / 2.6, -Float.pi / 5, 0)
        cameraRig.addChildNode(lightNode)  // follows the camera so shadows stay valid
    }

    private func startFresh() {
        terrain.reset()
        terrain.ensure(from: -K.rowsBehindKeep, to: K.rowsAhead)

        eagleNode?.removeFromParentNode()
        eagleNode = nil
        playerNode.removeFromParentNode()
        playerNode = VoxelFactory.wiskers(skin: Skins.all[skinIndex])
        playerNode.position = SCNVector3(0, 0, 0)
        isPaused = false
        scene.isPaused = false
        combo = 0
        lastForwardLand = -10
        hud?.hudCombo(0)
        worldNode.addChildNode(playerNode)

        playerRow = 0
        playerX = 0
        isHopping = false
        ridingLog = nil
        ridingRow = nil
        facing = .forward
        score = 0
        creatineCollected = 0
        idleTime = 0
        eagleTriggered = false
        camFrontier = 0
        cameraRig.position = SCNVector3(0, 0, 1.5)

        inputLock.lock(); inputQueue.removeAll(); inputLock.unlock()

        announcedWin = false
        announcedRecord = false
        lastSentState = nil
        mp.sendState(row: 0, x: 0, score: 0, alive: true)

        hud?.hudSetScore(0)
        hud?.hudSetCreatine(totalCreatine)
    }

    // MARK: - Public controls (main thread)

    func handleTap() {
        switch state {
        case .title:
            state = .playing
            hud?.hudStarted()
            enqueue(.forward)
        case .playing:
            enqueue(.forward)
        case .gameOver:
            restart()
        case .dying:
            break
        }
    }

    func handleSwipe(_ dir: Dir) {
        guard !isPaused, state == .playing || state == .title else { return }
        if state == .title, dir == .left || dir == .right {
            inputLock.lock()
            pendingSkinStep += dir == .right ? 1 : -1
            inputLock.unlock()
            return
        }
        if state == .title {
            state = .playing
            hud?.hudStarted()
        }
        enqueue(dir)
    }

    /// Requests pause/resume; only takes effect while a run is in progress.
    func setPaused(_ paused: Bool) {
        inputLock.lock()
        pendingPause = paused
        inputLock.unlock()
    }

    func presentTitle() {
        hud?.hudShowTitle(best: best, board: board)
    }

    func restart() {
        inputLock.lock()
        restartRequested = true
        inputQueue.removeAll()
        inputLock.unlock()
    }

    private func applyPendingRestart() {
        inputLock.lock()
        let shouldRestart = restartRequested
        restartRequested = false
        inputLock.unlock()
        guard shouldRestart else { return }
        state = .title
        startFresh()
        hud?.hudShowTitle(best: best, board: board)
    }

    private func applyPendingPause() {
        inputLock.lock()
        let request = pendingPause
        pendingPause = nil
        inputLock.unlock()
        guard let request, request != isPaused else { return }
        guard !request || state == .playing else { return }
        isPaused = request
        scene.isPaused = request
        hud?.hudPaused(request)
    }

    private func applyPendingSkin() {
        inputLock.lock()
        let step = pendingSkinStep
        pendingSkinStep = 0
        inputLock.unlock()
        guard step != 0, state == .title else { return }
        let count = Skins.all.count
        var idx = skinIndex
        repeat {
            idx = ((idx + step.signum()) % count + count) % count
        } while !Skins.isUnlocked(idx, total: totalCreatine)
        guard idx != skinIndex else { bumpAnimation(); return }
        skinIndex = idx
        UserDefaults.standard.set(idx, forKey: "skin")

        let old = playerNode
        let fresh = VoxelFactory.wiskers(skin: Skins.all[idx])
        fresh.position = SCNVector3(playerX, old.position.y, Float(playerRow))
        fresh.eulerAngles = old.eulerAngles
        old.removeFromParentNode()
        worldNode.addChildNode(fresh)
        playerNode = fresh
        let spin = SCNAction.rotateBy(x: 0, y: CGFloat.pi * 2, z: 0, duration: 0.4)
        spin.timingMode = .easeOut
        let jump = SCNAction.sequence([
            .moveBy(x: 0, y: 0.6, z: 0, duration: 0.2),
            .moveBy(x: 0, y: -0.6, z: 0, duration: 0.2),
        ])
        fresh.runAction(.group([spin, jump]))
        spawnBurst(at: SCNVector3(playerX, 0.5, Float(playerRow)), color: Skins.all[idx].fur, count: 10, size: 0.05...0.1, spread: 0.9)
        SoundManager.shared.play("coin", volume: 0.6)
        hud?.hudHaptic(.light)
        hud?.hudSkin(Skins.all[idx], next: Skins.next(after: totalCreatine), total: totalCreatine)
    }

    private func enqueue(_ dir: Dir) {
        inputLock.lock()
        if inputQueue.count < 2 { inputQueue.append(dir) }
        inputLock.unlock()
    }

    // MARK: - Render loop

    func renderer(_ renderer: SCNSceneRenderer, updateAtTime time: TimeInterval) {
        applyPendingRestart()
        applyPendingPause()
        applyPendingSkin()
        if lastTime < 0 { lastTime = time }
        let dt = Float(min(time - lastTime, 1.0 / 30.0))
        lastTime = time
        if isPaused { return }
        clock += dt
        if combo > 0, clock - lastForwardLand > K.comboWindow + Float(K.hopDuration) {
            combo = 0
            hud?.hudCombo(0)
        }

        processMultiplayer(dt: dt)
        updateRows(dt: dt)
        if autopilot { runAutopilot(dt: dt) }
        if state == .playing {
            drainInput()
            updateRiding(dt: dt)
            checkCollisions()
            updateCamera(dt: dt)
            checkEagleConditions(dt: dt)
        } else if state == .title || state == .dying {
            updateCamera(dt: dt, follow: state == .title)
        }
    }

    // MARK: - Rows / moving objects

    private func updateRows(dt: Float) {
        let camRow = Int(cameraRig.position.z.rounded())
        terrain.ensure(from: camRow - K.rowsBehindKeep, to: max(camRow, playerRow) + K.rowsAhead)

        let hazardBoost: Float = hazardBoostTimer > 0 ? K.garbageSpeedBoost : 1
        for (_, row) in terrain.rows {
            switch row.kind {
            case .road, .river:
                let boost = row.kind == .road ? hazardBoost : 1
                for obj in row.objects {
                    obj.x += row.dir * row.speed * boost * dt
                    if obj.x > K.wrapHalf { obj.x -= K.wrapHalf * 2 }
                    if obj.x < -K.wrapHalf { obj.x += K.wrapHalf * 2 }
                    obj.node.position.x = obj.x
                }
            case .rail:
                updateTrain(row, dt: dt)
            case .grass:
                break
            }
        }
    }

    private func updateTrain(_ row: Row, dt: Float) {
        // only run trains near the player/camera
        let nearPlayer = abs(Float(row.index) - Float(playerRow)) < 18
        row.trainTimer -= dt
        switch row.trainPhase {
        case .idle:
            if row.trainTimer <= 0 && nearPlayer {
                row.trainPhase = .warning
                row.trainTimer = 2.4
                row.blinkTimer = 0
                if abs(row.index - playerRow) < 14 { SoundManager.shared.play("bell", volume: 1.0) }
            }
        case .warning:
            row.blinkTimer -= dt
            if row.blinkTimer <= 0 {
                row.blinkTimer = 0.18
                row.blinkOn.toggle()
                setSignal(row, litA: row.blinkOn, litB: !row.blinkOn)
                if row.blinkOn && abs(row.index - playerRow) < 14 {
                    SoundManager.shared.play("bell", volume: 0.5)
                }
            }
            if row.trainTimer <= 0 {
                row.trainPhase = .running
                let (node, halfLen) = VoxelFactory.train(carriages: 5)
                if row.dir < 0 { node.eulerAngles.y = .pi }
                let startX = -row.dir * (K.wrapHalf + halfLen + 4)
                node.position = SCNVector3(startX, 0, 0)
                row.node.addChildNode(node)
                row.train = MovingObject(node: node, x: startX, halfLen: halfLen)
                if abs(row.index - playerRow) < 10 { SoundManager.shared.play("train", volume: 0.9) }
            }
        case .running:
            guard let train = row.train else { row.trainPhase = .idle; return }
            train.x += row.dir * 16.0 * dt
            train.node.position.x = train.x
            if abs(train.x) > K.wrapHalf + train.halfLen + 5 {
                train.node.removeFromParentNode()
                row.train = nil
                row.trainPhase = .idle
                row.trainTimer = Float.rand(2.5...7.0)
                setSignal(row, litA: false, litB: false)
            }
        }
    }

    private func setSignal(_ row: Row, litA: Bool, litB: Bool) {
        guard let signal = row.signal else { return }
        let on = UIColor(red: 1.0, green: 0.15, blue: 0.1, alpha: 1)
        let off = UIColor.black
        signal.childNode(withName: "lightA", recursively: true)?
            .geometry?.firstMaterial?.emission.contents = litA ? on : off
        signal.childNode(withName: "lightB", recursively: true)?
            .geometry?.firstMaterial?.emission.contents = litB ? on : off
    }

    // MARK: - Input / hopping

    private func drainInput() {
        guard !isHopping else { return }
        inputLock.lock()
        let dir = inputQueue.isEmpty ? nil : inputQueue.removeFirst()
        inputLock.unlock()
        guard let dir else { return }
        hop(dir)
    }

    private func hop(_ dir: Dir) {
        idleTime = 0
        facing = dir
        playerNode.runAction(.rotateTo(x: 0, y: CGFloat(dir.yaw), z: 0, duration: 0.05, usesShortestUnitArc: true))

        let targetRowIndex = playerRow + dir.dz
        guard let targetRow = terrain.rows[targetRowIndex] else { bumpAnimation(); return }

        // compute target x
        var targetX: Float
        if ridingLog != nil {
            // hopping off/along a log: continuous coords
            targetX = playerX + Float(dir.dx)
            if targetRow.kind != .river {
                targetX = targetX.rounded()
            }
        } else {
            targetX = (playerX + Float(dir.dx)).rounded()
        }

        // bounds / tree blocking (only for land rows)
        if targetRow.kind != .river {
            let clamped = max(Float(K.minCol), min(Float(K.maxCol), targetX))
            if clamped != targetX && dir.dz == 0 {
                bumpAnimation(); return  // side hop into the boundary
            }
            targetX = clamped
            if targetRow.blocked.contains(Int(targetX.rounded())) {
                bumpAnimation(); return
            }
        } else if dir.dz == 0, abs(targetX) > Float(K.maxCol) + 1.2 {
            bumpAnimation(); return
        }

        if dir.dz != 0, let current = terrain.rows[playerRow], current.kind == .road {
            let closeCall = current.objects.contains {
                let gap = abs($0.presentationX - playerX) - $0.halfLen - K.playerHalfWidth
                let approaching = ($0.presentationX - playerX) * current.dir < 0
                return approaching && gap < K.closeCallMargin
            }
            if closeCall {
                hud?.hudToast("CLOSE CALL!", color: Palette.hotOrange)
                hud?.hudHaptic(.medium)
            }
        }

        isHopping = true
        ridingLog = nil
        ridingRow = nil
        SoundManager.shared.play("hop", volume: 0.9)
        hud?.hudHaptic(.light)

        let targetY = targetRow.surfaceY
        let move = SCNAction.move(to: SCNVector3(targetX, targetY, Float(targetRowIndex)), duration: K.hopDuration)
        move.timingMode = .easeOut
        let up = SCNAction.moveBy(x: 0, y: K.hopHeight, z: 0, duration: K.hopDuration / 2)
        up.timingMode = .easeOut
        let down = SCNAction.moveBy(x: 0, y: -K.hopHeight, z: 0, duration: K.hopDuration / 2)
        down.timingMode = .easeIn
        let squash = SCNAction.sequence([
            .scale(to: 1.12, duration: K.hopDuration / 2),
            .scale(to: 1.0, duration: K.hopDuration / 2),
        ])
        playerNode.runAction(.group([move, .sequence([up, down]), squash])) { [weak self] in
            self?.landed(rowIndex: targetRowIndex, x: targetX)
        }
    }

    private func landed(rowIndex: Int, x: Float) {
        isHopping = false
        playerRow = rowIndex
        playerX = x
        guard state == .playing else { return }
        guard let row = terrain.rows[rowIndex] else { return }

        // score
        if rowIndex > score {
            score = rowIndex
            hud?.hudSetScore(score)
            combo = clock - lastForwardLand <= K.comboWindow + Float(K.hopDuration) ? combo + 1 : 1
            lastForwardLand = clock
            hud?.hudCombo(combo)
            if combo % K.comboBonusEvery == 0 {
                awardCreatine(1)
                SoundManager.shared.play("coin", volume: 0.7)
                hud?.hudToast("x\(combo) COMBO  +1", color: Palette.accentGold)
                hud?.hudHaptic(.success)
            }
            if score % K.milestoneEvery == 0 {
                hud?.hudBanner("\(score) ROWS!", color: Palette.accentGold)
            }
            if score > best && !announcedRecord && best > 0 {
                announcedRecord = true
                hud?.hudBanner("NEW RECORD!", color: Palette.accentGold)
                hud?.hudFlash(Palette.accentGold.withAlphaComponent(0.35))
                hud?.hudHaptic(.success)
            }
        }

        // landing feedback
        if row.kind == .river {
            spawnBurst(at: SCNVector3(x, 0.15, Float(rowIndex)), color: Palette.waterFoam, count: 4, size: 0.05...0.09, spread: 0.6)
        } else {
            spawnBurst(at: SCNVector3(x, 0.05, Float(rowIndex)), color: Palette.hudCream, count: 4, size: 0.05...0.09, spread: 0.6)
        }

        // creatine
        let col = Int(x.rounded())
        if let bottle = row.creatine[col] {
            row.creatine.removeValue(forKey: col)
            creatineCollected += 1
            awardCreatine(1)
            hud?.hudHaptic(.success)
            SoundManager.shared.play("coin", volume: 0.9)
            mp.sendGarbage(amount: 1)
            bottle.runAction(.sequence([
                .group([.moveBy(x: 0, y: 1.2, z: 0, duration: 0.25), .fadeOut(duration: 0.25)]),
                .removeFromParentNode(),
            ]))
            spawnBurst(at: SCNVector3(x, 0.6, Float(rowIndex)), color: Palette.accentGold, count: 10, size: 0.05...0.11, spread: 1.0)
        }

        sendStateIfChanged()

        // river landing: find a log or drown
        if row.kind == .river {
            if let log = row.objects.first(where: { abs($0.presentationX - x) <= $0.halfLen + 0.35 }) {
                ridingLog = log
                ridingRow = row
            } else {
                drown()
            }
        }
    }

    private func awardCreatine(_ amount: Int) {
        let before = totalCreatine
        totalCreatine += amount
        UserDefaults.standard.set(totalCreatine, forKey: "totalCreatine")
        hud?.hudSetCreatine(totalCreatine)
        for skin in Skins.all where skin.unlockAt > before && skin.unlockAt <= totalCreatine {
            hud?.hudBanner("\(skin.name) OTTER UNLOCKED!", color: skin.fur)
            hud?.hudFlash(skin.fur.withAlphaComponent(0.35))
        }
    }

    private func bumpAnimation() {
        guard !isHopping else { return }
        SoundManager.shared.play("bump", volume: 0.7)
        let squash = SCNAction.sequence([
            .scale(to: 0.85, duration: 0.05),
            .scale(to: 1.0, duration: 0.08),
        ])
        playerNode.runAction(squash)
    }

    // MARK: - Riding logs

    private func updateRiding(dt: Float) {
        guard ridingLog != nil, let row = ridingRow, !isHopping else { return }
        playerX += row.dir * row.speed * dt
        playerNode.position.x = playerX
        // drifted off the edge of the world
        if abs(playerX) > Float(K.maxCol) + 1.8 {
            triggerEagle()
        }
    }

    // MARK: - Collisions

    private func checkCollisions() {
        guard !eagleTriggered else { return }
        let p = playerNode.presentation.position
        let pRowIdx = Int(p.z.rounded())
        for idx in (pRowIdx - 1)...(pRowIdx + 1) {
            guard let row = terrain.rows[idx] else { continue }
            let rowZ = Float(row.index)
            let rowHalfDepth = row.kind == .rail ? K.trainRowHalfDepth : 0.45
            guard abs(p.z - rowZ) < rowHalfDepth else { continue }
            if row.kind == .road {
                for obj in row.objects where abs(obj.presentationX - p.x) < obj.halfLen + K.playerHalfWidth {
                    squashDeath()
                    return
                }
            } else if row.kind == .rail, let train = row.train {
                let collisionHalfLen = max(0, train.halfLen - K.trainCollisionInset)
                if abs(train.presentationX - p.x) < collisionHalfLen + K.trainPlayerHalfWidth {
                    squashDeath()
                    return
                }
            }
        }
    }

    // MARK: - Camera

    private func updateCamera(dt: Float, follow: Bool = true) {
        guard follow else { return }
        if state == .playing {
            camFrontier += K.cameraAutoAdvance * dt
        }
        camFrontier = max(camFrontier, Float(playerRow))
        let targetZ = camFrontier + 1.8
        let targetX = playerX * 0.45
        let k = 1 - exp(-dt * 3.2)
        cameraRig.position.x += (targetX - cameraRig.position.x) * k
        cameraRig.position.z += (targetZ - cameraRig.position.z) * k
    }

    // MARK: - Eagle / idle

    private func checkEagleConditions(dt: Float) {
        guard !eagleTriggered else { return }
        idleTime += dt
        let p = playerNode.presentation.position
        if idleTime > K.idleEagleSeconds || p.z < cameraRig.position.z - K.cameraBehindLimit {
            triggerEagle()
        }
    }

    private func triggerEagle() {
        guard !eagleTriggered, state == .playing else { return }
        eagleTriggered = true
        state = .dying
        SoundManager.shared.play("eagle", volume: 1.0)
        hud?.hudHaptic(.heavy)

        let p = playerNode.presentation.position
        let eagle = VoxelFactory.eagle()
        eagle.position = SCNVector3(p.x, 5.5, p.z - 14)
        worldNode.addChildNode(eagle)
        eagleNode = eagle

        let swoopIn = SCNAction.move(to: SCNVector3(p.x, 0.55, p.z), duration: 0.55)
        swoopIn.timingMode = .easeIn
        let flyOff = SCNAction.move(by: SCNVector3(0, 4.5, 26), duration: 1.1)
        flyOff.timingMode = .easeOut
        playerNode.removeAllActions()
        eagle.runAction(.sequence([
            swoopIn,
            .run { [weak self] _ in
                guard let self else { return }
                // grab Wiskers
                self.playerNode.removeFromParentNode()
                self.playerNode.position = SCNVector3(0, -0.55, 0.1)
                eagle.addChildNode(self.playerNode)
            },
            flyOff,
            .run { [weak self] _ in self?.finishGameOver() },
        ]))
    }

    // MARK: - Deaths

    private func squashDeath() {
        guard state == .playing else { return }
        state = .dying
        SoundManager.shared.play("hit", volume: 1.0)
        playerNode.removeAllActions()
        isHopping = false
        let p = playerNode.presentation.position
        playerNode.position = p
        playerNode.scale = SCNVector3(1.5, 0.08, 1.5)
        playerNode.position.y = terrain.rows[Int(p.z.rounded())]?.surfaceY ?? 0
        spawnBurst(at: SCNVector3(p.x, 0.4, p.z), color: Skins.all[skinIndex].fur, count: 14)
        shakeCamera()
        hud?.hudHaptic(.heavy)
        hud?.hudFlash(Palette.dangerRed.withAlphaComponent(0.45))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            self?.finishGameOver()
        }
    }

    private func drown() {
        guard state == .playing else { return }
        state = .dying
        SoundManager.shared.play("splash", volume: 1.0)
        isHopping = false
        spawnBurst(at: SCNVector3(playerX, 0.2, Float(playerRow)), color: Palette.waterFoam, count: 16)
        hud?.hudFlash(Palette.water.withAlphaComponent(0.4))
        hud?.hudHaptic(.heavy)
        let sink = SCNAction.group([
            .moveBy(x: 0, y: -1.2, z: 0, duration: 0.45),
            .fadeOut(duration: 0.45),
        ])
        sink.timingMode = .easeIn
        // drown() runs inside the hop action's completion; an action added to the
        // player there is dropped by the same evaluation pass, so defer it a frame
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.playerNode.removeAllActions()
            self.playerNode.runAction(sink)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            self?.finishGameOver()
        }
    }

    private func finishGameOver() {
        guard state == .dying else { return }
        state = .gameOver
        let newBest = score > best
        if newBest {
            best = score
            UserDefaults.standard.set(best, forKey: "best")
        }
        var placement: Int?
        if score > 0 {
            var updated = board + [score]
            updated.sort(by: >)
            updated = Array(updated.prefix(K.leaderboardSize))
            if let idx = updated.firstIndex(of: score) { placement = idx + 1 }
            board = updated
            UserDefaults.standard.set(board, forKey: "top5")
        }
        combo = 0
        hud?.hudCombo(0)
        hud?.hudGameOver(score: score, best: best, creatine: totalCreatine, newBest: newBest, placement: placement, duration: runDuration)
        mp.sendGameOver(score: score)
        mp.sendState(row: playerRow, x: playerX, score: score, alive: false)
    }

    // MARK: - Multiplayer

    private func processMultiplayer(dt: Float) {
        if hazardBoostTimer > 0 { hazardBoostTimer -= dt }

        var rosterChanged = false
        for event in mp.drainEvents() {
            switch event {
            case .connected(_, let peerIDs):
                for id in peerIDs { addPeer(id) }
                rosterChanged = true
            case .peerJoined(let id):
                addPeer(id)
                hud?.hudBanner("PLAYER \(id + 1) JOINED", color: Palette.rivalColor(id))
                rosterChanged = true
            case .peerState(let id, let row, let x, let score, let alive):
                guard let peer = peers[id] else { break }
                peer.score = max(peer.score, score)
                peer.alive = alive
                updateGhost(peer, id: id, row: row, x: x, alive: alive)
                rosterChanged = true
            case .peerGarbage(let id, let amount):
                guard state == .playing else { break }
                hazardBoostTimer += K.garbageBoostSeconds * Float(amount)
                hud?.hudBanner("PLAYER \(id + 1) SENT TRAFFIC!", color: Palette.rivalColor(id))
            case .peerGameOver(let id, let score):
                guard let peer = peers[id] else { break }
                peer.score = max(peer.score, score)
                peer.alive = false
                updateGhost(peer, id: id, row: nil, x: nil, alive: false)
                rosterChanged = true
                checkLastOtterStanding()
            case .opponentLeft(let id):
                peers[id]?.ghost?.removeFromParentNode()
                peers.removeValue(forKey: id)
                hud?.hudBanner("PLAYER \(id + 1) LEFT", color: Palette.rivalColor(id))
                rosterChanged = true
                checkLastOtterStanding()
            case .disconnected:
                for (_, peer) in peers { peer.ghost?.removeFromParentNode() }
                peers.removeAll()
                rosterChanged = true
            }
        }
        if rosterChanged { pushRivalHUD() }

        // periodic state while riding logs (position changes without landing)
        if state == .playing, ridingLog != nil {
            stateSendTimer -= dt
            if stateSendTimer <= 0 {
                stateSendTimer = 0.15
                sendStateIfChanged()
            }
        }
    }

    private func addPeer(_ id: Int) {
        guard peers[id] == nil else { return }
        peers[id] = Peer()
    }

    private func pushRivalHUD() {
        let rivals = peers.keys.sorted().map {
            RivalStatus(id: $0, score: peers[$0]!.score, alive: peers[$0]!.alive)
        }
        hud?.hudSetRivals(rivals)
    }

    private func sendStateIfChanged() {
        guard mp.isConnected else { return }
        if let last = lastSentState, last.row == playerRow, abs(last.x - playerX) < 0.05 { return }
        lastSentState = (playerRow, playerX)
        mp.sendState(row: playerRow, x: playerX, score: score, alive: state == .playing || state == .title)
    }

    private func updateGhost(_ peer: Peer, id: Int, row: Int?, x: Float?, alive: Bool) {
        if !alive {
            if let ghost = peer.ghost {
                peer.ghost = nil
                ghost.runAction(.sequence([
                    .group([.scale(to: 0.05, duration: 0.3), .fadeOut(duration: 0.3)]),
                    .removeFromParentNode(),
                ]))
            }
            return
        }
        guard let row, let x else { return }
        let ghost: SCNNode
        if let existing = peer.ghost {
            ghost = existing
        } else {
            ghost = VoxelFactory.wiskers()
            ghost.opacity = 0.55
            ghost.castsShadow = false
            let marker = VoxelFactory.box(w: 0.28, h: 0.14, l: 0.28,
                                          color: Palette.rivalColor(id), y: 1.15, chamfer: 0.02)
            marker.castsShadow = false
            ghost.addChildNode(marker)
            worldNode.addChildNode(ghost)
            peer.ghost = ghost
        }
        let y = terrain.rows[row]?.surfaceY ?? 0
        let move = SCNAction.move(to: SCNVector3(x, y, Float(row)), duration: 0.12)
        move.timingMode = .easeOut
        ghost.runAction(move)
    }

    private func checkLastOtterStanding() {
        guard !announcedWin, state == .playing, !peers.isEmpty else { return }
        guard peers.values.allSatisfy({ !$0.alive }) else { return }
        announcedWin = true
        hud?.hudBanner("LAST OTTER STANDING — YOU WIN!", color: Palette.accentGold)
    }

    // MARK: - Autopilot (debug)

    private func runAutopilot(dt: Float) {
        autoTimer -= dt
        guard autoTimer <= 0 else { return }
        switch state {
        case .title:
            handleTap()
            autoTimer = 0.5
        case .playing:
            if !isHopping, let dir = chooseAutoMove() { enqueue(dir) }
            autoTimer = Float.rand(0.25...0.45)
        case .gameOver:
            restart()
            autoTimer = 1.0
        case .dying:
            autoTimer = 0.5
        }
    }

    private func autoSafe(_ dir: Dir) -> Bool {
        let rowIdx = playerRow + dir.dz
        guard let row = terrain.rows[rowIdx] else { return false }
        let targetX = ridingLog != nil && row.kind == .river
            ? playerX + Float(dir.dx)
            : (playerX + Float(dir.dx)).rounded()
        switch row.kind {
        case .grass:
            return abs(targetX) <= Float(K.maxCol) && !row.blocked.contains(Int(targetX.rounded()))
        case .road:
            guard abs(targetX) <= Float(K.maxCol) else { return false }
            // no car close to the landing tile (or about to reach it)
            return !row.objects.contains {
                let x = $0.presentationX
                let future = x + row.dir * row.speed * 0.55
                return abs(x - targetX) < $0.halfLen + 1.0 || abs(future - targetX) < $0.halfLen + 1.0
            }
        case .rail:
            guard abs(targetX) <= Float(K.maxCol) else { return false }
            return row.trainPhase == .idle
        case .river:
            return row.objects.contains { abs($0.presentationX - targetX) <= $0.halfLen + 0.1 }
        }
    }

    private func chooseAutoMove() -> Dir? {
        if autoSafe(.forward) { return .forward }
        let laterals = [Dir.left, .right].shuffled().filter { autoSafe($0) }
        // when standing still is dangerous (idle) occasionally sidestep
        if let side = laterals.first, Float.random(in: 0..<1) < 0.5 { return side }
        return nil
    }

    // MARK: - Effects

    private func spawnBurst(at pos: SCNVector3, color: UIColor, count: Int,
                            size: ClosedRange<Float> = 0.06...0.14, spread: Float = 1.2) {
        for _ in 0..<count {
            let s = CGFloat(Float.rand(size))
            let piece = VoxelFactory.box(w: s, h: s, l: s, color: color, chamfer: 0)
            piece.position = pos
            piece.castsShadow = false
            worldNode.addChildNode(piece)
            let vx = CGFloat(Float.rand(-spread...spread))
            let vz = CGFloat(Float.rand(-spread...spread))
            let up = SCNAction.moveBy(x: vx * 0.4, y: CGFloat(Float.rand(0.5...1.1)), z: vz * 0.4, duration: 0.18)
            up.timingMode = .easeOut
            let downDist = CGFloat(Float.rand(0.7...1.3))
            let down = SCNAction.moveBy(x: vx * 0.6, y: -downDist, z: vz * 0.6, duration: 0.3)
            down.timingMode = .easeIn
            piece.runAction(.sequence([up, down, .fadeOut(duration: 0.12), .removeFromParentNode()]))
        }
    }

    private func shakeCamera() {
        let amt: CGFloat = 0.18
        let shake = SCNAction.sequence([
            .moveBy(x: amt, y: 0, z: -amt, duration: 0.04),
            .moveBy(x: -amt * 2, y: 0, z: amt * 2, duration: 0.08),
            .moveBy(x: amt, y: 0, z: -amt, duration: 0.04),
        ])
        cameraRig.runAction(shake)
    }
}
