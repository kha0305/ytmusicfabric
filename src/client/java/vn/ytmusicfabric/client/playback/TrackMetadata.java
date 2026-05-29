package vn.ytmusicfabric.client.playback;

import java.nio.file.Path;

public record TrackMetadata(String id, String title, String sourceUrl, Path cachedAudioPath, long durationSeconds) {
}
