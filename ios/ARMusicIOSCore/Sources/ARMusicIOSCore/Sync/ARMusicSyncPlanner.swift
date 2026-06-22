import Foundation

public enum ARMusicSyncPlanner {
    public static func buildPlan(
        localTracks: [ARMusicSyncTrack],
        remoteTracks: [ARMusicSyncTrack]
    ) -> ARMusicSyncPlan {
        let localById = Dictionary(uniqueKeysWithValues: localTracks.map { ($0.syncId, $0) })
        let remoteById = Dictionary(uniqueKeysWithValues: remoteTracks.map { ($0.syncId, $0) })

        let download = remoteTracks.filter { localById[$0.syncId] == nil }
        let upload = localTracks.filter { remoteById[$0.syncId] == nil }
        let conflicts = remoteTracks.compactMap { remote -> ARMusicSyncConflict? in
            guard let local = localById[remote.syncId] else {
                return nil
            }
            return hasMetadataConflict(local, remote)
                ? ARMusicSyncConflict(local: local, remote: remote)
                : nil
        }

        return ARMusicSyncPlan(download: download, upload: upload, conflicts: conflicts)
    }

    private static func hasMetadataConflict(_ local: ARMusicSyncTrack, _ remote: ARMusicSyncTrack) -> Bool {
        local.title != remote.title ||
            local.artist != remote.artist ||
            local.album != remote.album ||
            local.relativePath != remote.relativePath
    }
}
