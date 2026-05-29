package vn.ytmusicfabric.client.config;

import java.nio.file.Path;

public record YtMusicConfig(
	String ytDlpPath,
	String ffmpegPath,
	int volumePercent,
	boolean hudVisible,
	Path configFile,
	Path cacheDir,
	Path toolsDir
) {
}
