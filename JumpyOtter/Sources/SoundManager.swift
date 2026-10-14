import AVFoundation

/// Preloads short WAV effects and plays them with a small pool per sound
/// so rapid retriggers (e.g. hopping) can overlap.
final class SoundManager {
    static let shared = SoundManager()

    private var pools: [String: [AVAudioPlayer]] = [:]
    private var cursors: [String: Int] = [:]
    private let queue = DispatchQueue(label: "sound")

    var enabled: Bool = UserDefaults.standard.object(forKey: "soundOn") as? Bool ?? true {
        didSet { UserDefaults.standard.set(enabled, forKey: "soundOn") }
    }

    private init() {
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        for name in ["hop", "bump", "coin", "splash", "hit", "train", "eagle", "bell"] {
            guard let url = Bundle.main.url(forResource: name, withExtension: "wav") else { continue }
            var pool: [AVAudioPlayer] = []
            for _ in 0..<3 {
                if let p = try? AVAudioPlayer(contentsOf: url) {
                    p.prepareToPlay()
                    pool.append(p)
                }
            }
            pools[name] = pool
            cursors[name] = 0
        }
    }

    func play(_ name: String, volume: Float = 1.0) {
        guard enabled else { return }
        queue.async { [self] in
            guard let pool = pools[name], !pool.isEmpty else { return }
            let i = (cursors[name] ?? 0) % pool.count
            cursors[name] = i + 1
            let p = pool[i]
            p.volume = volume
            p.currentTime = 0
            p.play()
        }
    }
}
