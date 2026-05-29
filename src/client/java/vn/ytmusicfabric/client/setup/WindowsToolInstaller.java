package vn.ytmusicfabric.client.setup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import vn.ytmusicfabric.client.config.YtMusicConfig;
import vn.ytmusicfabric.client.config.YtMusicConfigManager;

public class WindowsToolInstaller {
	private static final URI YT_DLP_WINDOWS_URI = URI.create("https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe");
	private static final URI FFMPEG_WINDOWS_URI = URI.create("https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip");

	private final YtMusicConfigManager configManager = new YtMusicConfigManager();
	private final HttpClient httpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(30))
		.build();

	public InstallationReport install(boolean installYtDlp, boolean installFfmpeg) throws IOException, InterruptedException {
		if (!isWindows()) {
			throw new IOException("Lệnh tự tải hiện chỉ hỗ trợ Windows. Hãy tự cài yt-dlp và ffmpeg trên hệ điều hành này.");
		}

		YtMusicConfig config = configManager.load();
		List<String> installed = new ArrayList<>();
		List<String> skipped = new ArrayList<>();

		if (installYtDlp) {
			Path ytDlpPath = installYtDlp(config);
			if (Files.size(ytDlpPath) > 0) {
				installed.add("yt-dlp");
			} else {
				skipped.add("yt-dlp");
			}
		}

		if (installFfmpeg) {
			Path ffmpegDir = installFfmpeg(config);
			if (Files.exists(ffmpegDir.resolve("ffmpeg.exe"))) {
				installed.add("ffmpeg");
			} else {
				skipped.add("ffmpeg");
			}
		}

		return new InstallationReport(config.toolsDir(), installed, skipped);
	}

	private Path installYtDlp(YtMusicConfig config) throws IOException, InterruptedException {
		Path targetPath = config.toolsDir().resolve("yt-dlp.exe");
		if (Files.exists(targetPath) && Files.size(targetPath) > 0) {
			return targetPath;
		}

		downloadToFile(YT_DLP_WINDOWS_URI, targetPath);
		return targetPath;
	}

	private Path installFfmpeg(YtMusicConfig config) throws IOException, InterruptedException {
		Path tempZip = Files.createTempFile("ytmusicfabric-ffmpeg-", ".zip");
		Path ffmpegBinDir = config.toolsDir().resolve("ffmpeg").resolve("bin");
		Path ffmpegExe = ffmpegBinDir.resolve("ffmpeg.exe");
		Path ffprobeExe = ffmpegBinDir.resolve("ffprobe.exe");

		if (Files.exists(ffmpegExe) && Files.exists(ffprobeExe) && Files.size(ffmpegExe) > 0 && Files.size(ffprobeExe) > 0) {
			return ffmpegBinDir;
		}

		try {
			downloadToFile(FFMPEG_WINDOWS_URI, tempZip);
			Files.createDirectories(ffmpegBinDir);
			extractFfmpegBinaries(tempZip, ffmpegBinDir);
			return ffmpegBinDir;
		} finally {
			Files.deleteIfExists(tempZip);
		}
	}

	private void downloadToFile(URI uri, Path targetPath) throws IOException, InterruptedException {
		Files.createDirectories(targetPath.getParent());
		Path tempFile = Files.createTempFile(targetPath.getParent(), "download-", ".tmp");

		try {
			HttpRequest request = HttpRequest.newBuilder(uri)
				.GET()
				.timeout(Duration.ofMinutes(10))
				.build();
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("Tải file thất bại từ " + uri + " với mã HTTP " + response.statusCode());
			}

			try (InputStream body = response.body()) {
				Files.copy(body, tempFile, StandardCopyOption.REPLACE_EXISTING);
			}

			Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	private void extractFfmpegBinaries(Path zipFile, Path targetDir) throws IOException {
		boolean extractedFfmpeg = false;
		boolean extractedFfprobe = false;

		try (InputStream fileInputStream = Files.newInputStream(zipFile);
			 ZipInputStream zipInputStream = new ZipInputStream(fileInputStream)) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				String normalizedName = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
				if (entry.isDirectory()) {
					continue;
				}

				if (normalizedName.endsWith("/bin/ffmpeg.exe")) {
					copyZipEntry(zipInputStream, targetDir.resolve("ffmpeg.exe"));
					extractedFfmpeg = true;
				} else if (normalizedName.endsWith("/bin/ffprobe.exe")) {
					copyZipEntry(zipInputStream, targetDir.resolve("ffprobe.exe"));
					extractedFfprobe = true;
				}
			}
		}

		if (!extractedFfmpeg || !extractedFfprobe) {
			throw new IOException("Không tìm thấy đủ file ffmpeg.exe và ffprobe.exe trong gói FFmpeg.");
		}
	}

	private void copyZipEntry(ZipInputStream inputStream, Path targetFile) throws IOException {
		Files.createDirectories(targetFile.getParent());
		Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
	}

	private boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	public record InstallationReport(Path toolsDir, List<String> installedTools, List<String> skippedTools) {
	}
}
