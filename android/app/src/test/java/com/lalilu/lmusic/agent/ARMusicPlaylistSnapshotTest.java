package com.lalilu.lmusic.agent;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ARMusicPlaylistSnapshotTest {
    @Test
    public void crossPlatformSnapshotFixtureMatchesDesktop() {
        PlaylistRecord first = new PlaylistRecord(
                "p-α",
                "夜航",
                "说明",
                "content://封面/1",
                1_783_256_122_959L,
                1_783_256_123_999L,
                Arrays.asList("audio-sha256-abc", "sha256-旧"));
        PlaylistRecord empty = new PlaylistRecord(
                "p-empty", "空", "", "", 1L, 2L, Collections.emptyList());
        PlaylistTombstone deleted = new PlaylistTombstone(
                "p-deleted", 1_783_256_124_000L);
        PlaylistTrackTombstone removed = new PlaylistTrackTombstone(
                "p-α", "sha256-移除", 1_783_256_124_001L);

        assertEquals(
                "playlists-sha256-7b717f23f63b9ecec2d9a9b8b3be42d6",
                ARMusicPlaylistSnapshotKt.armusicPlaylistSnapshotId(
                        Arrays.asList(first, empty),
                        Collections.singletonList(deleted),
                        Collections.singletonList(removed)));
    }
}
