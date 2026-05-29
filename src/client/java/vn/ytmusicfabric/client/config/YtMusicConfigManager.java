package vn.ytmusicfabric.client.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.loader.api.FabricLoader;

public class YtMusicConfigManager {
	private static final int DEFAULT_VOLUME_PERCENT = 100;
	private static final boolean DEFAULT_HUD_VISIBLE = true;
	private static final String DEFAULT_CONFIG = """
# Đường dẫn đầy đủ tới yt-dlp. Để trống nếu bạn đã thêm yt-dlp vào PATH.
ytDlpPath=

# Đường dẫn đầy đủ tới ffmpeg hoặc thư mục chứa ffmpeg. Để trống nếu bạn đã thêm ffmpeg vào PATH.
ffmpegPath=

# Âm lượng riêng của mod, từ 0 tới 100. Âm lượng thực tế còn chịu ảnh hưởng bởi thanh âm lượng Minecraft.
volumePercent=100

# Có hiển thị bảng HUD/scoreboard của mod hay không.
hudVisible=true
""";

	private final Path configFile;
	private final Path cacheDir;
	private final Path toolsDir;

	public YtMusicConfigManager() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		this.configFile = configDir.resolve("ytmusicfabric.properties");
		this.cacheDir = configDir.resolve("ytmusicfabric-cache");
		this.toolsDir = configDir.resolve("ytmusicfabric-tools");
	}

	public YtMusicConfig load() throws IOException {
		Files.createDirectories(configFile.getParent());
		Files.createDirectories(cacheDir);
		Files.createDirectories(toolsDir);

		if (Files.notExists(configFile)) {
			Files.writeString(
				configFile,
				DEFAULT_CONFIG,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW
			);
		}

		SimpleProperties properties = SimpleProperties.parse(Files.readString(configFile, StandardCharsets.UTF_8));

		return new YtMusicConfig(
			normalize(properties.get("ytDlpPath")),
			normalize(properties.get("ffmpegPath")),
			parseVolume(properties.get("volumePercent")),
			parseHudVisible(properties.get("hudVisible")),
			configFile,
			cacheDir,
			toolsDir
		);
	}

	public YtMusicConfig saveVolumePercent(int requestedPercent) throws IOException {
		YtMusicConfig current = load();
		YtMusicConfig updated = new YtMusicConfig(
			current.ytDlpPath(),
			current.ffmpegPath(),
			clampVolume(requestedPercent),
			current.hudVisible(),
			current.configFile(),
			current.cacheDir(),
			current.toolsDir()
		);
		write(updated);
		return updated;
	}

	public YtMusicConfig saveHudVisible(boolean requestedHudVisible) throws IOException {
		YtMusicConfig current = load();
		YtMusicConfig updated = new YtMusicConfig(
			current.ytDlpPath(),
			current.ffmpegPath(),
			current.volumePercent(),
			requestedHudVisible,
			current.configFile(),
			current.cacheDir(),
			current.toolsDir()
		);
		write(updated);
		return updated;
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}

		return value.trim();
	}

	private int parseVolume(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return DEFAULT_VOLUME_PERCENT;
		}

		try {
			return clampVolume(Integer.parseInt(rawValue.trim()));
		} catch (NumberFormatException exception) {
			return DEFAULT_VOLUME_PERCENT;
		}
	}

	private boolean parseHudVisible(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return DEFAULT_HUD_VISIBLE;
		}

		return Boolean.parseBoolean(rawValue.trim());
	}

	private int clampVolume(int requestedPercent) {
		return Math.max(0, Math.min(100, requestedPercent));
	}

	private void write(YtMusicConfig config) throws IOException {
		String content = """
# Đường dẫn đầy đủ tới yt-dlp. Để trống nếu bạn đã thêm yt-dlp vào PATH.
ytDlpPath=%s

# Đường dẫn đầy đủ tới ffmpeg hoặc thư mục chứa ffmpeg. Để trống nếu bạn đã thêm ffmpeg vào PATH.
ffmpegPath=%s

# Âm lượng riêng của mod, từ 0 tới 100. Âm lượng thực tế còn chịu ảnh hưởng bởi thanh âm lượng Minecraft.
volumePercent=%d

# Có hiển thị bảng HUD/scoreboard của mod hay không.
hudVisible=%s
""".formatted(
			escapeValue(config.ytDlpPath()),
			escapeValue(config.ffmpegPath()),
			config.volumePercent(),
			Boolean.toString(config.hudVisible())
		);

		Files.writeString(
			config.configFile(),
			content,
			StandardCharsets.UTF_8,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.CREATE
		);
	}

	private String escapeValue(String value) {
		return value.replace("\\", "/");
	}

	private record SimpleProperties(java.util.Map<String, String> values) {
		static SimpleProperties parse(String content) {
			java.util.Map<String, String> parsed = new java.util.LinkedHashMap<>();
			for (String rawLine : content.split("\\R")) {
				String line = rawLine.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}

				int separatorIndex = line.indexOf('=');
				if (separatorIndex < 0) {
					continue;
				}

				String key = line.substring(0, separatorIndex).trim();
				String value = line.substring(separatorIndex + 1).trim();
				parsed.put(key, value);
			}
			return new SimpleProperties(parsed);
		}

		String get(String key) {
			return values.get(key);
		}
	}
}
