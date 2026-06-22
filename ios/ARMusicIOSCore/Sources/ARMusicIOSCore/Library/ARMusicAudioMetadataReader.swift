import AVFoundation
import Foundation

public struct ARMusicAudioMetadata: Equatable, Sendable {
    public var title: String?
    public var artist: String?
    public var album: String?
    public var durationSeconds: Int64

    public init(
        title: String? = nil,
        artist: String? = nil,
        album: String? = nil,
        durationSeconds: Int64 = 0
    ) {
        self.title = title
        self.artist = artist
        self.album = album
        self.durationSeconds = durationSeconds
    }
}

public enum ARMusicAudioMetadataReader {
    public static func read(fileURL: URL) -> ARMusicAudioMetadata {
        let asset = AVURLAsset(url: fileURL)
        let metadata = asset.commonMetadata
        let rawDuration = CMTimeGetSeconds(asset.duration)
        let duration = rawDuration.isFinite && rawDuration > 0
            ? Int64(rawDuration.rounded())
            : 0

        return ARMusicAudioMetadata(
            title: stringValue(in: metadata, key: .commonKeyTitle),
            artist: stringValue(in: metadata, key: .commonKeyArtist),
            album: stringValue(in: metadata, key: .commonKeyAlbumName),
            durationSeconds: duration
        )
    }

    private static func stringValue(in metadata: [AVMetadataItem], key: AVMetadataKey) -> String? {
        metadata
            .first { $0.commonKey == key }?
            .stringValue?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nonEmpty
    }
}
