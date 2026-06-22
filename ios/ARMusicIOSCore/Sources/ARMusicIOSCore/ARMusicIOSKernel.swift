import Foundation

public final class ARMusicIOSKernel {
    public let store: ARMusicLibraryStore
    public let discoveryClient: ARMusicLanDiscoveryClient
    public let syncClient: ARMusicLanSyncClient
    public let syncEngine: ARMusicSyncEngine
    public let playback: ARMusicPlaybackSession

    public init(
        rootDirectory: URL? = nil,
        catalogDirectory: URL? = nil,
        deviceName: String = "ARMusic iOS"
    ) throws {
        let store = try ARMusicLibraryStore(
            rootDirectory: rootDirectory,
            catalogDirectory: catalogDirectory,
            deviceName: deviceName
        )
        let syncClient = ARMusicLanSyncClient()

        self.store = store
        self.discoveryClient = ARMusicLanDiscoveryClient()
        self.syncClient = syncClient
        self.syncEngine = ARMusicSyncEngine(store: store, client: syncClient)
        self.playback = ARMusicPlaybackSession()
    }

    @discardableResult
    public func importFiles(_ urls: [URL]) throws -> [ARMusicSyncTrack] {
        try urls.map { try store.importExternalFile(from: $0) }
    }

    public func manifest() -> ARMusicSyncManifest {
        store.buildManifest()
    }

    public func discoverPeers(timeout: TimeInterval = 2.5) async -> [ARMusicDiscoveredPeer] {
        await discoveryClient.discover(timeout: timeout)
    }

    public func planSync(with baseUrl: String) async throws -> (remote: ARMusicSyncManifest, plan: ARMusicSyncPlan) {
        try await syncEngine.fetchRemotePlan(baseUrl: baseUrl)
    }
}
