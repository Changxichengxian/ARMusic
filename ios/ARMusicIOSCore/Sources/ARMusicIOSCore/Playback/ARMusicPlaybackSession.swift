import AVFoundation
import Foundation

public enum ARMusicPlaybackState: Equatable, Sendable {
    case idle
    case loading
    case playing
    case paused
}

public final class ARMusicPlaybackSession: NSObject {
    public private(set) var currentTrack: ARMusicSyncTrack?
    public private(set) var state: ARMusicPlaybackState = .idle

    private var player: AVPlayer?

    public override init() {
        super.init()
    }

    public func configureAudioSession() throws {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playback,
            mode: .default,
            options: [.allowAirPlay, .allowBluetoothA2DP]
        )
        try session.setActive(true)
        #endif
    }

    public func load(track: ARMusicSyncTrack, from store: ARMusicLibraryStore, autoplay: Bool = false) throws {
        state = .loading
        let fileURL = try store.fileURL(for: track)
        currentTrack = track
        player = AVPlayer(url: fileURL)

        if autoplay {
            play()
        } else {
            state = .paused
        }
    }

    public func play() {
        player?.play()
        state = .playing
    }

    public func pause() {
        player?.pause()
        state = .paused
    }

    public func stop() {
        player?.pause()
        player?.seek(to: .zero)
        state = .idle
    }

    public var currentTimeSeconds: Double {
        guard let player else {
            return 0
        }
        let seconds = CMTimeGetSeconds(player.currentTime())
        return seconds.isFinite ? seconds : 0
    }
}
