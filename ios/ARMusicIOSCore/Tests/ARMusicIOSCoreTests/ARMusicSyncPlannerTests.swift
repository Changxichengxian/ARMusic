import XCTest
@testable import ARMusicIOSCore

final class ARMusicSyncPlannerTests: XCTestCase {
    func testBuildPlanSeparatesDownloadUploadAndConflicts() {
        let sharedLocal = ARMusicSyncTrack(
            syncId: "sha256-shared",
            title: "A",
            artist: "B",
            album: "C",
            relativePath: "A.mp3"
        )
        let sharedRemote = ARMusicSyncTrack(
            syncId: "sha256-shared",
            title: "A changed",
            artist: "B",
            album: "C",
            relativePath: "A.mp3"
        )
        let localOnly = ARMusicSyncTrack(
            syncId: "sha256-local",
            title: "Local",
            artist: "B",
            album: "C",
            relativePath: "Local.mp3"
        )
        let remoteOnly = ARMusicSyncTrack(
            syncId: "sha256-remote",
            title: "Remote",
            artist: "B",
            album: "C",
            relativePath: "Remote.mp3"
        )

        let plan = ARMusicSyncPlanner.buildPlan(
            localTracks: [sharedLocal, localOnly],
            remoteTracks: [sharedRemote, remoteOnly]
        )

        XCTAssertEqual(plan.download, [remoteOnly])
        XCTAssertEqual(plan.upload, [localOnly])
        XCTAssertEqual(plan.conflicts, [ARMusicSyncConflict(local: sharedLocal, remote: sharedRemote)])
    }
}
