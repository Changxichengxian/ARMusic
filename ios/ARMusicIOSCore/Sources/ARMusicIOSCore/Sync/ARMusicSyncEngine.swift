import Foundation

public final class ARMusicSyncEngine {
    private let store: ARMusicLibraryStore
    private let client: ARMusicLanSyncClient

    public init(store: ARMusicLibraryStore, client: ARMusicLanSyncClient = ARMusicLanSyncClient()) {
        self.store = store
        self.client = client
    }

    public func fetchRemotePlan(baseUrl: String) async throws -> (remote: ARMusicSyncManifest, plan: ARMusicSyncPlan) {
        let remote = try await client.fetchManifest(baseUrl: baseUrl)
        let local = store.buildManifest()
        let plan = ARMusicSyncPlanner.buildPlan(
            localTracks: local.tracks,
            remoteTracks: remote.tracks
        )
        return (remote, plan)
    }

    @discardableResult
    public func downloadMissingTracks(
        from baseUrl: String,
        tracks: [ARMusicSyncTrack]
    ) async throws -> [ARMusicSyncTrack] {
        var imported: [ARMusicSyncTrack] = []
        for track in tracks {
            let temporaryURL = try await client.downloadTrack(baseUrl: baseUrl, syncId: track.syncId)
            do {
                imported.append(try store.importDownloadedFile(temporaryURL, remoteTrack: track))
            } catch {
                try? FileManager.default.removeItem(at: temporaryURL)
                throw error
            }
        }
        return imported
    }

    public func uploadMissingTracks(
        to baseUrl: String,
        tracks: [ARMusicSyncTrack]
    ) async throws {
        for track in tracks {
            let fileURL = try store.fileURL(for: track)
            try await client.uploadTrack(baseUrl: baseUrl, track: track, fileURL: fileURL)
        }
    }
}
