package vn.ytmusicfabric.client.playback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import vn.ytmusicfabric.client.config.YtMusicConfig;
import vn.ytmusicfabric.client.config.YtMusicConfigManager;

public class YtDlpBridge {
	private static final Duration METADATA_TIMEOUT = Duration.ofMinutes(2);
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);

	private final YtMusicConfigManager configManager = new YtMusicConfigManager();
	private final AtomicReference<Process> activeProcess = new AtomicReference<>();

	public TrackMetadata resolveTrack(String url) throws IOException, InterruptedException {
		YtMusicConfig config = configManager.load();
		String ytDlpCommand = resolveYtDlpCommand(config);

		ProcessResult result = runTrackedCommand(
			List.of(
				ytDlpCommand,
				"--no-playlist",
				"--dump-single-json",
				"--skip-download",
				url
			),
			METADATA_TIMEOUT
		);

		if (result.exitCode() != 0) {
			throw buildCommandException("yt-dlp không lấy được metadata.", result, config.configFile());
		}

		String stdout = result.stdout().trim();
		if (stdout.isBlank()) {
			throw new IOException("yt-dlp không trả về metadata JSON.");
		}

		JsonObject metadata = JsonParser.parseString(stdout).getAsJsonObject();
		String id = readString(metadata, "id", "yt-" + System.currentTimeMillis());
		String title = readString(metadata, "title", "Không rõ tiêu đề").replace('\n', ' ').trim();
		String sourceUrl = readString(metadata, "webpage_url", url);
		long durationSeconds = readLong(metadata, "duration", -1L);
		Path cachedAudioPath = config.cacheDir().resolve(sanitizeTrackId(id) + ".wav");

		return new TrackMetadata(id, title, sourceUrl, cachedAudioPath, durationSeconds);
	}

	public Path downloadAudio(TrackMetadata metadata) throws IOException, InterruptedException {
		Objects.requireNonNull(metadata, "metadata");

		YtMusicConfig config = configManager.load();
		String ytDlpCommand = resolveYtDlpCommand(config);
		String ffmpegLocation = resolveFfmpegLocation(config);

		Path targetFile = metadata.cachedAudioPath();
		if (Files.exists(targetFile) && Files.size(targetFile) > 0) {
			return targetFile;
		}

		Files.createDirectories(targetFile.getParent());

		List<String> command = new ArrayList<>();
		command.add(ytDlpCommand);
		command.add("--no-playlist");
		command.add("--extract-audio");
		command.add("--audio-format");
		command.add("wav");
		command.add("--audio-quality");
		command.add("0");

		if (!ffmpegLocation.isBlank()) {
			command.add("--ffmpeg-location");
			command.add(ffmpegLocation);
		}

		command.add("--output");
		command.add(buildOutputTemplate(targetFile));
		command.add(metadata.sourceUrl());

		ProcessResult result = runTrackedCommand(command, DOWNLOAD_TIMEOUT);
		if (result.exitCode() != 0) {
			throw buildCommandException("yt-dlp không tải được audio WAV.", result, config.configFile());
		}

		if (Files.notExists(targetFile) || Files.size(targetFile) == 0) {
			throw new IOException("yt-dlp đã chạy xong nhưng không tạo được file WAV tại " + targetFile);
		}

		return targetFile;
	}

	public void cancelActiveProcess() {
		Process process = activeProcess.getAndSet(null);
		if (process != null) {
			process.destroyForcibly();
		}
	}

	public ToolStatus inspectTools() throws IOException, InterruptedException {
		YtMusicConfig config = configManager.load();
		return new ToolStatus(inspectYtDlp(config), inspectFfmpeg(config), config.configFile());
	}

	public int clearCache(Path exceptFile) throws IOException {
		YtMusicConfig config = configManager.load();
		if (Files.notExists(config.cacheDir())) {
			return 0;
		}

		int deletedCount = 0;
		try (Stream<Path> files = Files.list(config.cacheDir())) {
			for (Path file : files.toList()) {
				if (!Files.isRegularFile(file)) {
					continue;
				}
				if (exceptFile != null && file.equals(exceptFile)) {
					continue;
				}

				Files.deleteIfExists(file);
				deletedCount++;
			}
		}

		return deletedCount;
	}

	private String resolveYtDlpCommand(YtMusicConfig config) throws IOException, InterruptedException {
		if (!config.ytDlpPath().isBlank()) {
			String configuredYtDlp = resolveConfiguredExecutable(config.ytDlpPath(), "yt-dlp");
			verifyTool(configuredYtDlp, "--version", "yt-dlp", config.configFile());
			return configuredYtDlp;
		}

		Path bundledYtDlp = config.toolsDir().resolve(isWindows() ? "yt-dlp.exe" : "yt-dlp");
		if (Files.exists(bundledYtDlp)) {
			verifyTool(bundledYtDlp.toString(), "--version", "yt-dlp", config.configFile());
			return bundledYtDlp.toString();
		}

		for (String candidate : defaultCandidates("yt-dlp")) {
			if (isToolAvailable(candidate, "--version")) {
				return candidate;
			}
		}

		throw new IOException("Không tìm thấy yt-dlp. Hãy dùng /ytmusic install ytdlp hoặc chỉnh đường dẫn trong " + config.configFile());
	}

	private String resolveFfmpegLocation(YtMusicConfig config) throws IOException, InterruptedException {
		if (!config.ffmpegPath().isBlank()) {
			String configuredFfmpeg = resolveConfiguredExecutable(config.ffmpegPath(), "ffmpeg");
			verifyTool(configuredFfmpeg, "-version", "ffmpeg", config.configFile());
			return configuredFfmpeg;
		}

		Path bundledFfmpeg = config.toolsDir().resolve("ffmpeg").resolve("bin").resolve(isWindows() ? "ffmpeg.exe" : "ffmpeg");
		if (Files.exists(bundledFfmpeg)) {
			verifyTool(bundledFfmpeg.toString(), "-version", "ffmpeg", config.configFile());
			return bundledFfmpeg.toString();
		}

		for (String candidate : defaultCandidates("ffmpeg")) {
			if (isToolAvailable(candidate, "-version")) {
				return "";
			}
		}

		throw new IOException("Không tìm thấy ffmpeg. Hãy dùng /ytmusic install ffmpeg hoặc chỉnh đường dẫn trong " + config.configFile());
	}

	private ToolPresence inspectYtDlp(YtMusicConfig config) throws IOException, InterruptedException {
		if (!config.ytDlpPath().isBlank()) {
			String configuredYtDlp = resolveConfiguredExecutable(config.ytDlpPath(), "yt-dlp");
			return new ToolPresence(
				isToolAvailable(configuredYtDlp, "--version"),
				configuredYtDlp,
				"Cấu hình thủ công"
			);
		}

		Path bundledYtDlp = config.toolsDir().resolve(isWindows() ? "yt-dlp.exe" : "yt-dlp");
		if (Files.exists(bundledYtDlp)) {
			return new ToolPresence(
				isToolAvailable(bundledYtDlp.toString(), "--version"),
				bundledYtDlp.toString(),
				"Tự tải trong thư mục config"
			);
		}

		for (String candidate : defaultCandidates("yt-dlp")) {
			if (isToolAvailable(candidate, "--version")) {
				return new ToolPresence(true, "PATH", "Tìm thấy trong PATH");
			}
		}

		return new ToolPresence(false, "", "Chưa tìm thấy");
	}

	private ToolPresence inspectFfmpeg(YtMusicConfig config) throws IOException, InterruptedException {
		if (!config.ffmpegPath().isBlank()) {
			String configuredFfmpeg = resolveConfiguredExecutable(config.ffmpegPath(), "ffmpeg");
			return new ToolPresence(
				isToolAvailable(configuredFfmpeg, "-version"),
				configuredFfmpeg,
				"Cấu hình thủ công"
			);
		}

		Path bundledFfmpeg = config.toolsDir().resolve("ffmpeg").resolve("bin").resolve(isWindows() ? "ffmpeg.exe" : "ffmpeg");
		if (Files.exists(bundledFfmpeg)) {
			return new ToolPresence(
				isToolAvailable(bundledFfmpeg.toString(), "-version"),
				bundledFfmpeg.toString(),
				"Tự tải trong thư mục config"
			);
		}

		for (String candidate : defaultCandidates("ffmpeg")) {
			if (isToolAvailable(candidate, "-version")) {
				return new ToolPresence(true, "PATH", "Tìm thấy trong PATH");
			}
		}

		return new ToolPresence(false, "", "Chưa tìm thấy");
	}

	private void verifyTool(String command, String versionArgument, String toolName, Path configFile) throws IOException, InterruptedException {
		if (isToolAvailable(command, versionArgument)) {
			return;
		}

		throw new IOException("Không thể chạy " + toolName + " từ cấu hình hiện tại. Hãy kiểm tra " + configFile);
	}

	private boolean isToolAvailable(String command, String versionArgument) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command, versionArgument);
		Process process;

		try {
			process = builder.start();
		} catch (IOException exception) {
			return false;
		}

		boolean finished = process.waitFor(10, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			return false;
		}

		return process.exitValue() == 0;
	}

	private ProcessResult runTrackedCommand(List<String> command, Duration timeout) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).start();
		activeProcess.set(process);

		StringBuilder stdout = new StringBuilder();
		StringBuilder stderr = new StringBuilder();
		Thread stdoutThread = consumeStream(process.getInputStream(), stdout);
		Thread stderrThread = consumeStream(process.getErrorStream(), stderr);

		try {
			boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IOException("Lệnh chạy quá thời gian cho phép: " + String.join(" ", command));
			}
		} catch (InterruptedException interruptedException) {
			process.destroyForcibly();
			throw interruptedException;
		} finally {
			joinQuietly(stdoutThread);
			joinQuietly(stderrThread);
			activeProcess.compareAndSet(process, null);
		}

		return new ProcessResult(process.exitValue(), stdout.toString(), stderr.toString());
	}

	private Thread consumeStream(InputStream stream, StringBuilder output) {
		Thread thread = Thread.ofPlatform()
			.daemon(true)
			.name("ytmusicfabric-process-stream")
			.unstarted(() -> {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						if (!output.isEmpty()) {
							output.append(System.lineSeparator());
						}
						output.append(line);
					}
				} catch (IOException ignored) {
				}
			});
		thread.start();
		return thread;
	}

	private void joinQuietly(Thread thread) {
		try {
			thread.join();
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
		}
	}

	private IOException buildCommandException(String prefix, ProcessResult result, Path configFile) {
		StringBuilder message = new StringBuilder(prefix);
		if (!result.stderr().isBlank()) {
			message.append(' ').append(result.stderr().trim());
		}
		if (!result.stdout().isBlank()) {
			message.append(" | stdout: ").append(result.stdout().trim());
		}
		message.append(" | Kiểm tra cấu hình tại ").append(configFile);
		return new IOException(message.toString());
	}

	private String buildOutputTemplate(Path targetFile) {
		String fileName = targetFile.getFileName().toString();
		if (fileName.endsWith(".wav")) {
			fileName = fileName.substring(0, fileName.length() - 4);
		}

		return targetFile.getParent().resolve(fileName + ".%(ext)s").toString();
	}

	private List<String> defaultCandidates(String baseCommand) {
		if (isWindows()) {
			return List.of(baseCommand + ".exe", baseCommand);
		}

		return List.of(baseCommand);
	}

	private boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private String sanitizeTrackId(String value) {
		return value.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	private String resolveConfiguredExecutable(String configuredPath, String executableBaseName) {
		Path path = Path.of(configuredPath);
		if (Files.isDirectory(path)) {
			return path.resolve(isWindows() ? executableBaseName + ".exe" : executableBaseName).toString();
		}

		return configuredPath;
	}

	private String readString(JsonObject object, String propertyName, String fallback) {
		if (!object.has(propertyName) || object.get(propertyName).isJsonNull()) {
			return fallback;
		}

		return object.get(propertyName).getAsString();
	}

	private long readLong(JsonObject object, String propertyName, long fallback) {
		if (!object.has(propertyName) || object.get(propertyName).isJsonNull()) {
			return fallback;
		}

		try {
			return object.get(propertyName).getAsLong();
		} catch (RuntimeException exception) {
			return fallback;
		}
	}

	private record ProcessResult(int exitCode, String stdout, String stderr) {
	}

	public record ToolPresence(boolean available, String location, String source) {
	}

	public record ToolStatus(ToolPresence ytDlp, ToolPresence ffmpeg, Path configFile) {
	}
}
