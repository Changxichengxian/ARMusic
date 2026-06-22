import Foundation

public struct ARMusicSyncHealth: Codable, Equatable, Sendable {
    public var ok: Bool
    public var name: String
    public var time: String

    public init(ok: Bool = false, name: String = "", time: String = "") {
        self.ok = ok
        self.name = name
        self.time = time
    }
}

public struct ARMusicSyncTrack: Codable, Equatable, Hashable, Identifiable, Sendable {
    public var id: String { syncId }

    public var syncId: String
    public var title: String
    public var artist: String
    public var album: String
    public var durationSeconds: Int64
    public var sizeBytes: Int64
    public var relativePath: String
    public var modifiedAt: String?
    public var playSeconds: Int64
    public var lastPlayedAt: String?
    public var source: String

    public init(
        syncId: String,
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int64 = 0,
        sizeBytes: Int64 = 0,
        relativePath: String,
        modifiedAt: String? = nil,
        playSeconds: Int64 = 0,
        lastPlayedAt: String? = nil,
        source: String = "ios"
    ) {
        self.syncId = syncId
        self.title = title
        self.artist = artist
        self.album = album
        self.durationSeconds = durationSeconds
        self.sizeBytes = sizeBytes
        self.relativePath = relativePath
        self.modifiedAt = modifiedAt
        self.playSeconds = playSeconds
        self.lastPlayedAt = lastPlayedAt
        self.source = source
    }
}

public struct ARMusicSyncManifest: Codable, Equatable, Sendable {
    public var libraryId: String
    public var deviceName: String
    public var generatedAt: String
    public var tracks: [ARMusicSyncTrack]

    public init(
        libraryId: String,
        deviceName: String,
        generatedAt: String,
        tracks: [ARMusicSyncTrack] = []
    ) {
        self.libraryId = libraryId
        self.deviceName = deviceName
        self.generatedAt = generatedAt
        self.tracks = tracks
    }
}

public struct ARMusicSyncConflict: Equatable, Sendable {
    public var local: ARMusicSyncTrack
    public var remote: ARMusicSyncTrack

    public init(local: ARMusicSyncTrack, remote: ARMusicSyncTrack) {
        self.local = local
        self.remote = remote
    }
}

public struct ARMusicSyncPlan: Equatable, Sendable {
    public var download: [ARMusicSyncTrack]
    public var upload: [ARMusicSyncTrack]
    public var conflicts: [ARMusicSyncConflict]

    public var totalChanges: Int {
        download.count + upload.count + conflicts.count
    }

    public init(
        download: [ARMusicSyncTrack],
        upload: [ARMusicSyncTrack],
        conflicts: [ARMusicSyncConflict]
    ) {
        self.download = download
        self.upload = upload
        self.conflicts = conflicts
    }
}

public struct ARMusicDiscoveredPeer: Codable, Equatable, Hashable, Sendable {
    public var name: String
    public var baseUrl: String
    public var addresses: [String]

    public init(name: String, baseUrl: String, addresses: [String] = []) {
        self.name = name
        self.baseUrl = baseUrl
        self.addresses = addresses
    }
}
