package vn.ytmusicfabric.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import vn.ytmusicfabric.YtMusicFabricMod;
import vn.ytmusicfabric.client.audio.AudioStreamPlayer;
import vn.ytmusicfabric.client.audio.AudioStreamPlayer.PlaybackOutcome;
import vn.ytmusicfabric.client.audio.AudioStreamPlayer.PlaybackOutcome.Reason;
import vn.ytmusicfabric.client.config.YtMusicConfig;
import vn.ytmusicfabric.client.config.YtMusicConfigManager;
import vn.ytmusicfabric.client.playback.PlaybackPhase;
import vn.ytmusicfabric.client.playback.TrackMetadata;
import vn.ytmusicfabric.client.playback.YtDlpBridge;
import vn.ytmusicfabric.client.playback.YtDlpBridge.ToolStatus;
import vn.ytmusicfabric.client.playlist.PlaylistShareCodec;
import vn.ytmusicfabric.client.setup.WindowsToolInstaller;
import vn.ytmusicfabric.client.setup.WindowsToolInstaller.InstallationReport;

public class YtMusicController {
	private static final String PREFIX = "[YT Nhạc] ";
	private static final int MAX_BATCH_LINKS = 5;

	private final Supplier<MinecraftClient> clientSupplier;
	private final ExecutorService executor;
	private final AtomicLong sessionCounter = new AtomicLong();
	private final ConcurrentLinkedQueue<String> queuedUrls = new ConcurrentLinkedQueue<>();
	private final YtDlpBridge ytDlpBridge = new YtDlpBridge();
	private final WindowsToolInstaller toolInstaller = new WindowsToolInstaller();
	private final YtMusicConfigManager configManager = new YtMusicConfigManager();

	private volatile PlaybackPhase phase = PlaybackPhase.IDLE;
	private volatile TrackMetadata currentTrack;
	private volatile AudioStreamPlayer currentPlayer;
	private volatile Future<?> activeTask;
	private volatile long activeSessionId;
	private volatile int configuredVolumePercent = 100;
	private volatile String lastInfoMessage = "Sẵn sàng.";
	private volatile boolean loopEnabled;
	private volatile boolean hudVisible = true;
	private volatile List<String> playlistUrls = List.of();
	private volatile int playlistIndex = -1;

	public YtMusicController(Supplier<MinecraftClient> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.executor = Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "ytmusicfabric-worker");
			thread.setDaemon(true);
			return thread;
		});
		reloadVolumeConfig();
	}

	public void play(String rawUrl) {
		List<String> urls = parseInputUrls(rawUrl);
		if (urls.isEmpty()) {
			return;
		}

		setPlaylist(urls);
		if (urls.size() > 1) {
			queuedUrls.addAll(urls.subList(1, urls.size()));
			notifyInfo("Đã nhận " + urls.size() + " link. Bắt đầu phát link đầu tiên, còn " + queuedUrls.size() + " bài trong hàng chờ.");
		}

		startPlaybackSession(urls.get(0), true);
	}

	public void pause() {
		AudioStreamPlayer player = currentPlayer;
		if (player == null || phase != PlaybackPhase.PLAYING) {
			notifyInfo("Hiện không có bài nào đang phát để tạm dừng.");
			return;
		}

		player.pause();
		phase = PlaybackPhase.PAUSED;
		notifyInfo("Đã tạm dừng.");
	}

	public void resume() {
		AudioStreamPlayer player = currentPlayer;
		if (player == null || phase != PlaybackPhase.PAUSED) {
			notifyInfo("Không có bài nào đang tạm dừng.");
			return;
		}

		player.resume();
		phase = PlaybackPhase.PLAYING;
		applyCurrentVolume();
		notifyInfo("Đã tiếp tục phát.");
	}

	public void stopWithFeedback() {
		long sessionId = sessionCounter.incrementAndGet();
		activeSessionId = sessionId;
		boolean stopped = cancelCurrentWork();
		clearPlaylist();
		phase = PlaybackPhase.IDLE;
		currentTrack = null;

		if (stopped) {
			notifyInfo("Đã dừng phát nhạc.");
			return;
		}

		notifyInfo("Hiện không có bài nào đang chạy.");
	}

	public void stopSilently() {
		long sessionId = sessionCounter.incrementAndGet();
		activeSessionId = sessionId;
		cancelCurrentWork();
		clearPlaylist();
		phase = PlaybackPhase.IDLE;
		currentTrack = null;
	}

	public void reportStatus() {
		notifyInfo(buildStatusMessage());
	}

	public String describeStatusForUi() {
		return buildStatusMessage();
	}

	public PlaylistSnapshot getPlaylistSnapshot() {
		List<String> snapshot = playlistUrls;
		if (snapshot.isEmpty()) {
			return new PlaylistSnapshot(List.of(), -1, loopEnabled);
		}

		List<PlaylistEntry> entries = new ArrayList<>(snapshot.size());
		for (int index = 0; index < snapshot.size(); index++) {
			entries.add(new PlaylistEntry(index + 1, resolvePlaylistEntryLabel(snapshot.get(index), index), resolvePlaylistEntryState(index)));
		}

		return new PlaylistSnapshot(List.copyOf(entries), playlistIndex, loopEnabled);
	}

	public boolean isLoopEnabled() {
		return loopEnabled;
	}

	public boolean isHudVisible() {
		return hudVisible;
	}

	public void toggleLoop() {
		setLoopEnabled(!loopEnabled);
	}

	public void toggleHudVisibility() {
		setHudVisible(!hudVisible);
	}

	public void setLoopEnabled(boolean enabled) {
		loopEnabled = enabled;
		notifyInfo(enabled ? "Đã bật lặp playlist hiện tại." : "Đã tắt lặp playlist.");
	}

	public void setHudVisible(boolean visible) {
		hudVisible = visible;
		try {
			YtMusicConfig updatedConfig = configManager.saveHudVisible(visible);
			hudVisible = updatedConfig.hudVisible();
		} catch (IOException exception) {
			notifyError("Đã đổi HUD tạm thời nhưng không lưu được cấu hình: " + summarizeError(exception));
			return;
		}
		notifyInfo(visible ? "Đã hiện scoreboard của mod." : "Đã ẩn scoreboard của mod.");
	}

	public void reportPlaylist() {
		notifyInfo(buildPlaylistMessage());
	}

	public void reportPlaylistCode() {
		String code = createPlaylistCodeFromCurrentPlaylist();
		if (code == null) {
			return;
		}

		notifyInfo("Playlist code: " + code);
	}

	public String createPlaylistCodeFromCurrentPlaylist() {
		List<String> currentUrls = playlistUrls;
		if (currentUrls.isEmpty()) {
			notifyError("Chưa có playlist để tạo code. Hãy phát ít nhất 1 link trước.");
			return null;
		}

		return createPlaylistCodeFromUrls(currentUrls);
	}

	public String createPlaylistCodeFromUrls(List<String> rawUrls) {
		List<String> normalizedUrls = normalizeUrlList(rawUrls);
		if (normalizedUrls.isEmpty()) {
			return null;
		}

		try {
			return PlaylistShareCodec.encode(normalizedUrls);
		} catch (IllegalArgumentException exception) {
			notifyError(summarizeError(exception));
			return null;
		}
	}

	public List<String> parsePlaylistCode(String rawCode) {
		List<String> decodedUrls;
		try {
			decodedUrls = PlaylistShareCodec.decode(rawCode);
		} catch (IllegalArgumentException exception) {
			notifyError(summarizeError(exception));
			return List.of();
		}

		List<String> normalizedUrls = normalizeUrlList(decodedUrls);
		if (normalizedUrls.isEmpty()) {
			return List.of();
		}

		notifyInfo("Đã nạp playlist code với " + normalizedUrls.size() + " link.");
		return normalizedUrls;
	}

	public void playPlaylistCode(String rawCode) {
		List<String> urls = parsePlaylistCode(rawCode);
		if (urls.isEmpty()) {
			return;
		}

		play(String.join(" ", urls));
	}

	public List<String> getCurrentPlaylistUrls() {
		return List.copyOf(playlistUrls);
	}

	public void notifyUiInfo(String message) {
		notifyInfo(message);
	}

	public void notifyUiError(String message) {
		notifyError(message);
	}

	public void clearUpcomingPlaylist() {
		if (playlistUrls.isEmpty()) {
			notifyInfo("Playlist hiện đang trống.");
			return;
		}

		if (queuedUrls.isEmpty()) {
			notifyInfo("Không còn bài nào phía sau để xoá.");
			return;
		}

		queuedUrls.clear();
		if (playlistIndex >= 0 && playlistIndex < playlistUrls.size()) {
			playlistUrls = List.copyOf(playlistUrls.subList(0, playlistIndex + 1));
		}

		notifyInfo("Đã xoá các bài còn lại trong playlist. Bài hiện tại vẫn tiếp tục phát.");
	}

	public void reportToolStatus() {
		try {
			notifyInfo(buildToolStatusMessage(ytDlpBridge.inspectTools()));
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			notifyError(summarizeError(exception));
		}
	}

	public String describeToolStatusForUi() {
		try {
			return buildToolStatusMessage(ytDlpBridge.inspectTools());
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return summarizeError(exception);
		}
	}

	public void refreshToolStatusAsync(boolean announceInChat, Consumer<String> callback) {
		Objects.requireNonNull(callback, "callback");
		CompletableFuture
			.supplyAsync(() -> {
				try {
					String message = buildToolStatusMessage(ytDlpBridge.inspectTools());
					if (announceInChat) {
						notifyInfo(message);
					}
					return message;
				} catch (IOException | InterruptedException exception) {
					if (exception instanceof InterruptedException) {
						Thread.currentThread().interrupt();
					}

					String errorMessage = summarizeError(exception);
					if (announceInChat) {
						notifyError(errorMessage);
					}
					return errorMessage;
				}
			})
			.thenAccept(message -> {
				MinecraftClient client = clientSupplier.get();
				if (client != null) {
					client.execute(() -> callback.accept(message));
					return;
				}

				callback.accept(message);
			});
	}

	public void installTools(boolean installYtDlp, boolean installFfmpeg) {
		if (!installYtDlp && !installFfmpeg) {
			notifyInfo("Hãy chọn ytdlp, ffmpeg hoặc all.");
			return;
		}

		long sessionId = sessionCounter.incrementAndGet();
		activeSessionId = sessionId;
		cancelCurrentWork();

		String target = installYtDlp && installFfmpeg ? "yt-dlp và ffmpeg" : installYtDlp ? "yt-dlp" : "ffmpeg";
		notifyInfo("Đang tải " + target + " vào thư mục cấu hình của profile...");

		activeTask = executor.submit(() -> installToolsInBackground(sessionId, installYtDlp, installFfmpeg));
	}

	public void clearCache() {
		try {
			Path currentAudio = currentTrack == null ? null : currentTrack.cachedAudioPath();
			int deletedFiles = ytDlpBridge.clearCache(currentAudio);
			notifyInfo("Đã xoá " + deletedFiles + " file cache.");
		} catch (IOException exception) {
			notifyError(summarizeError(exception));
		}
	}

	public int getConfiguredVolumePercent() {
		return configuredVolumePercent;
	}

	public void reportVolume() {
		notifyInfo("Âm lượng mod hiện tại: " + configuredVolumePercent + "%.");
	}

	public void setVolumePercent(int requestedPercent) {
		commitVolumePercent(requestedPercent, true);
	}

	public void previewVolumePercent(int requestedPercent) {
		int clampedVolume = Math.max(0, Math.min(100, requestedPercent));
		configuredVolumePercent = clampedVolume;
		applyCurrentVolume();
	}

	public void commitVolumePercent(int requestedPercent, boolean notifyUser) {
		int clampedVolume = Math.max(0, Math.min(100, requestedPercent));
		configuredVolumePercent = clampedVolume;
		applyCurrentVolume();

		try {
			YtMusicConfig updatedConfig = configManager.saveVolumePercent(configuredVolumePercent);
			configuredVolumePercent = updatedConfig.volumePercent();
		} catch (IOException exception) {
			notifyError("Đã áp dụng âm lượng tạm thời nhưng không lưu được cấu hình: " + summarizeError(exception));
			return;
		}

		if (notifyUser) {
			notifyInfo("Đã đặt âm lượng riêng của mod thành " + configuredVolumePercent + "%.");
		}
	}

	public void adjustVolume(int delta) {
		setVolumePercent(configuredVolumePercent + delta);
	}

	public void softReloadClient() {
		MinecraftClient client = clientSupplier.get();
		if (client == null) {
			notifyError("Không tìm thấy Minecraft client để reload.");
			return;
		}

		reloadVolumeConfig();
		applyCurrentVolume();
		notifyInfo("Đang reload mềm tài nguyên và cấu hình của mod...");

		client.reloadResources().whenComplete((unused, throwable) -> {
			if (throwable != null) {
				YtMusicFabricMod.LOGGER.error("Reload mềm YT Music Fabric thất bại.", throwable);
				notifyError("Reload mềm thất bại: " + summarizeThrowable(throwable));
				return;
			}

			client.execute(() -> {
				reloadVolumeConfig();
				applyCurrentVolume();
				notifyInfo("Đã reload mềm YT Music Fabric. Nếu vừa thay file jar hoặc code Java, bạn vẫn cần mở lại client.");
			});
		});
	}

	public void showHelp() {
		notifyInfo("Lệnh chính: /ytmusic play <link> [link2 ... link5], /ytmusic pause, /ytmusic resume, /ytmusic stop, /ytmusic status");
		notifyInfo("Lệnh thêm: /ytmusic loop [on|off], /ytmusic hud [on|off], /ytmusic playlist [show|clear|code], /ytmusic playlist code play <code>, /ytmusic install <ytdlp|ffmpeg|all>, /ytmusic volume [0-100], /ytmusic tools, /ytmusic cache clear, /ytmusic reload, /ytmusic gui");
	}

	public void onClientTick(MinecraftClient client) {
		AudioStreamPlayer player = currentPlayer;
		if (player != null) {
			player.setVolumeFactor(computeEffectiveVolumeFactor(client));
		}
	}

	public HudSnapshot getHudSnapshot() {
		TrackMetadata metadata = currentTrack;
		AudioStreamPlayer player = currentPlayer;
		long currentMillis = player == null ? 0L : player.getPlaybackPositionMillis();
		long totalMillis = metadata != null && metadata.durationSeconds() > 0 ? metadata.durationSeconds() * 1000L : 0L;
		boolean visible = phase != PlaybackPhase.IDLE || metadata != null || player != null;
		visible = visible && hudVisible;
		boolean determinate = totalMillis > 0L;
		float progressFraction = determinate
			? Math.max(0.0f, Math.min(1.0f, currentMillis / (float) totalMillis))
			: 0.0f;

		return new HudSnapshot(
			visible,
			phase,
			resolveHudTitle(metadata),
			buildHudTimeline(phase, currentMillis, totalMillis),
			progressFraction,
			determinate,
			configuredVolumePercent,
			loopEnabled
		);
	}

	public String getLastInfoMessage() {
		return lastInfoMessage;
	}

	public void shutdown() {
		stopSilently();
		executor.shutdownNow();
	}

	private void startPlaybackSession(String url, boolean cancelExisting) {
		long sessionId = sessionCounter.incrementAndGet();
		activeSessionId = sessionId;
		if (cancelExisting) {
			cancelCurrentWork();
		}
		currentTrack = null;
		phase = PlaybackPhase.RESOLVING;
		notifyInfo("Đang kiểm tra link và lấy metadata từ yt-dlp...");

		activeTask = executor.submit(() -> resolveAndPlay(sessionId, url));
	}

	private void resolveAndPlay(long sessionId, String url) {
		TrackMetadata resolvedMetadata = null;
		try {
			TrackMetadata metadata = ytDlpBridge.resolveTrack(url);
			resolvedMetadata = metadata;
			if (!isCurrentSession(sessionId)) {
				return;
			}

			currentTrack = metadata;
			phase = PlaybackPhase.DOWNLOADING;
			notifyInfo("Đã nhận bài: " + metadata.title() + ". Đang tải audio WAV...");

			Path audioPath = ytDlpBridge.downloadAudio(metadata);
			if (!isCurrentSession(sessionId)) {
				return;
			}

			AudioStreamPlayer player = new AudioStreamPlayer(audioPath, outcome -> handlePlaybackOutcome(sessionId, metadata, outcome));
			player.setVolumeFactor(computeEffectiveVolumeFactor(clientSupplier.get()));
			currentPlayer = player;
			phase = PlaybackPhase.PLAYING;

			notifyInfo("Đang phát: " + metadata.title() + durationSuffix(metadata));
			player.start();
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
		} catch (IOException | RuntimeException exception) {
			if (!isCurrentSession(sessionId)) {
				return;
			}

			YtMusicFabricMod.LOGGER.error("Không thể phát nhạc từ YouTube.", exception);
			String trackLabel = resolvedMetadata != null ? resolvedMetadata.title() : url;
			notifyError("Không phát được " + trackLabel + ": " + summarizeError(exception));
			if (!startNextQueuedTrack(sessionId, "Đang chuyển sang link tiếp theo trong playlist...", false)) {
				phase = PlaybackPhase.ERROR;
				currentTrack = null;
				clearPlaylist();
			}
		} finally {
			if (isCurrentSession(sessionId)) {
				activeTask = null;
			}
		}
	}

	private void installToolsInBackground(long sessionId, boolean installYtDlp, boolean installFfmpeg) {
		try {
			InstallationReport report = toolInstaller.install(installYtDlp, installFfmpeg);
			if (!isCurrentSession(sessionId)) {
				return;
			}

			notifyInfo(buildInstallationMessage(report));
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
		} catch (IOException | RuntimeException exception) {
			if (!isCurrentSession(sessionId)) {
				return;
			}

			YtMusicFabricMod.LOGGER.error("Không thể tự tải công cụ cho mod.", exception);
			notifyError(summarizeError(exception));
		} finally {
			if (isCurrentSession(sessionId)) {
				activeTask = null;
			}
		}
	}

	private void handlePlaybackOutcome(long sessionId, TrackMetadata metadata, PlaybackOutcome outcome) {
		if (!isCurrentSession(sessionId)) {
			return;
		}

		currentPlayer = null;

		if (outcome.reason() == Reason.FAILED) {
			if (outcome.error() != null) {
				YtMusicFabricMod.LOGGER.error("Lỗi khi phát file audio {}", metadata.cachedAudioPath(), outcome.error());
			}
			notifyError("Không thể phát file WAV đã tải về cho " + metadata.title() + ".");
			if (!startNextQueuedTrack(sessionId, "Đang chuyển sang bài tiếp theo trong playlist...", false)) {
				phase = PlaybackPhase.ERROR;
				currentTrack = null;
				clearPlaylist();
			}
			return;
		}

		if (outcome.reason() == Reason.COMPLETED) {
			notifyInfo("Đã phát xong: " + metadata.title());
			if (!startNextQueuedTrack(sessionId, "Đang chuẩn bị bài tiếp theo...", true)) {
				phase = PlaybackPhase.IDLE;
				currentTrack = null;
				clearPlaylist();
			}
		}
	}

	private boolean cancelCurrentWork() {
		boolean didSomething = false;

		Future<?> task = activeTask;
		if (task != null) {
			task.cancel(true);
			activeTask = null;
			didSomething = true;
		}

		ytDlpBridge.cancelActiveProcess();

		AudioStreamPlayer player = currentPlayer;
		if (player != null) {
			player.stop();
			currentPlayer = null;
			didSomething = true;
		}

		return didSomething;
	}

	private boolean startNextQueuedTrack(long sessionId, String message, boolean allowLoopRestart) {
		String nextUrl = queuedUrls.poll();
		boolean restartedFromLoop = false;
		if (nextUrl == null) {
			if (!allowLoopRestart) {
				return false;
			}

			nextUrl = restartPlaylistFromBeginning();
			if (nextUrl == null) {
				return false;
			}
			restartedFromLoop = true;
		} else {
			advancePlaylistIndex();
		}
		final String queuedUrl = nextUrl;

		currentTrack = null;
		phase = PlaybackPhase.RESOLVING;
		if (restartedFromLoop) {
			notifyInfo("Đã quay lại đầu playlist vì chế độ lặp đang bật. Còn " + queuedUrls.size() + " bài sau link này.");
		} else {
			notifyInfo(message + " Còn " + queuedUrls.size() + " bài sau link này.");
		}
		activeTask = executor.submit(() -> resolveAndPlay(sessionId, queuedUrl));
		return true;
	}

	private boolean isCurrentSession(long sessionId) {
		return activeSessionId == sessionId;
	}

	private void setPlaylist(List<String> urls) {
		clearQueue();
		playlistUrls = List.copyOf(urls);
		playlistIndex = urls.isEmpty() ? -1 : 0;
	}

	private void clearPlaylist() {
		clearQueue();
		playlistUrls = List.of();
		playlistIndex = -1;
	}

	private String restartPlaylistFromBeginning() {
		List<String> snapshot = playlistUrls;
		if (!loopEnabled || snapshot.isEmpty()) {
			return null;
		}

		clearQueue();
		if (snapshot.size() > 1) {
			queuedUrls.addAll(snapshot.subList(1, snapshot.size()));
		}
		playlistIndex = 0;
		return snapshot.get(0);
	}

	private void advancePlaylistIndex() {
		List<String> snapshot = playlistUrls;
		if (snapshot.isEmpty()) {
			playlistIndex = -1;
			return;
		}

		playlistIndex = Math.min(playlistIndex + 1, snapshot.size() - 1);
	}

	private void reloadVolumeConfig() {
		try {
			YtMusicConfig config = configManager.load();
			configuredVolumePercent = config.volumePercent();
			hudVisible = config.hudVisible();
		} catch (IOException exception) {
			YtMusicFabricMod.LOGGER.warn("Không thể nạp cấu hình âm lượng, dùng mặc định 100%.", exception);
			configuredVolumePercent = 100;
			hudVisible = true;
		}
	}

	private void applyCurrentVolume() {
		MinecraftClient client = clientSupplier.get();
		AudioStreamPlayer player = currentPlayer;
		if (client == null || player == null) {
			return;
		}

		player.setVolumeFactor(computeEffectiveVolumeFactor(client));
	}

	private float computeEffectiveVolumeFactor(MinecraftClient client) {
		if (client == null || client.options == null) {
			return configuredVolumePercent / 100.0f;
		}

		float customVolume = configuredVolumePercent / 100.0f;
		float masterVolume = client.options.getSoundVolume(SoundCategory.MASTER);
		float recordsVolume = client.options.getSoundVolume(SoundCategory.RECORDS);
		float effectiveVolume = customVolume * masterVolume * recordsVolume;
		return Math.max(0.0f, Math.min(1.0f, effectiveVolume));
	}

	private String buildStatusMessage() {
		TrackMetadata metadata = currentTrack;
		String progressSuffix = buildProgressSuffix(metadata);
		String volumeSuffix = " Âm lượng mod: " + configuredVolumePercent + "%. Lặp: " + (loopEnabled ? "bật" : "tắt") + ". HUD: " + (hudVisible ? "bật" : "tắt") + ".";
		String queueSuffix = queuedUrls.isEmpty() ? "" : " Hàng chờ: " + queuedUrls.size() + " bài.";
		String playlistSuffix = buildPlaylistPositionSuffix();

		return switch (phase) {
			case IDLE -> "Trạng thái: đang rảnh." + volumeSuffix + playlistSuffix + queueSuffix;
			case RESOLVING -> "Trạng thái: đang phân tích link YouTube..." + volumeSuffix + playlistSuffix + queueSuffix;
			case DOWNLOADING -> "Trạng thái: đang tải WAV cho " + safeTitle(metadata) + "." + volumeSuffix + playlistSuffix + queueSuffix;
			case PLAYING -> "Trạng thái: đang phát " + safeTitle(metadata) + progressSuffix + "." + volumeSuffix + playlistSuffix + queueSuffix;
			case PAUSED -> "Trạng thái: đang tạm dừng " + safeTitle(metadata) + progressSuffix + "." + volumeSuffix + playlistSuffix + queueSuffix;
			case ERROR -> "Trạng thái: lần phát gần nhất gặp lỗi." + volumeSuffix + playlistSuffix + queueSuffix;
		};
	}

	private String buildPlaylistPositionSuffix() {
		List<String> snapshot = playlistUrls;
		if (snapshot.isEmpty() || playlistIndex < 0) {
			return "";
		}

		int displayIndex = Math.min(playlistIndex + 1, snapshot.size());
		return " Playlist: " + displayIndex + "/" + snapshot.size() + ".";
	}

	private String buildPlaylistMessage() {
		List<String> snapshot = playlistUrls;
		if (snapshot.isEmpty()) {
			return "Playlist hiện đang trống.";
		}

		StringBuilder builder = new StringBuilder("Playlist ");
		builder.append(Math.min(Math.max(playlistIndex + 1, 1), snapshot.size())).append('/').append(snapshot.size());
		builder.append(loopEnabled ? " [lặp bật]: " : " [lặp tắt]: ");

		for (int index = 0; index < snapshot.size(); index++) {
			if (index > 0) {
				builder.append(" | ");
			}
			if (index == playlistIndex) {
				builder.append(">");
			}
			builder.append(index + 1).append('.');
			builder.append(resolvePlaylistEntryLabel(snapshot.get(index), index));
		}

		return builder.toString();
	}

	private String buildToolStatusMessage(ToolStatus toolStatus) {
		return "Công cụ: yt-dlp="
			+ formatToolPresence(toolStatus.ytDlp())
			+ ", ffmpeg="
			+ formatToolPresence(toolStatus.ffmpeg())
			+ ". File cấu hình: "
			+ toolStatus.configFile();
	}

	private String formatToolPresence(YtDlpBridge.ToolPresence presence) {
		if (!presence.available()) {
			return "thiếu";
		}

		if (presence.location().isBlank()) {
			return "có";
		}

		return "có (" + presence.location() + ")";
	}

	private String buildProgressSuffix(TrackMetadata metadata) {
		if (metadata == null) {
			return "";
		}

		AudioStreamPlayer player = currentPlayer;
		if (player == null) {
			if (metadata.durationSeconds() > 0) {
				return " (" + formatDuration(0L) + " / " + formatDuration(metadata.durationSeconds() * 1000L) + ")";
			}
			return "";
		}

		long currentMillis = player.getPlaybackPositionMillis();
		if (metadata.durationSeconds() > 0) {
			return " (" + formatDuration(currentMillis) + " / " + formatDuration(metadata.durationSeconds() * 1000L) + ")";
		}

		return " (" + formatDuration(currentMillis) + ")";
	}

	private String buildHudTimeline(PlaybackPhase phase, long currentMillis, long totalMillis) {
		if (phase == PlaybackPhase.RESOLVING) {
			return "Đang phân tích link...";
		}

		if (phase == PlaybackPhase.DOWNLOADING) {
			return "Đang tải audio...";
		}

		if (totalMillis > 0L) {
			return formatDuration(currentMillis) + " / " + formatDuration(totalMillis);
		}

		if (currentMillis > 0L) {
			return "Đã phát " + formatDuration(currentMillis);
		}

		return "Đang chờ timeline...";
	}

	private String durationSuffix(TrackMetadata metadata) {
		if (metadata.durationSeconds() <= 0) {
			return "";
		}

		return " (" + formatDuration(metadata.durationSeconds() * 1000L) + ")";
	}

	private String formatDuration(long millis) {
		long totalSeconds = Math.max(0L, millis / 1000L);
		long minutes = totalSeconds / 60L;
		long seconds = totalSeconds % 60L;
		return "%02d:%02d".formatted(minutes, seconds);
	}

	private String safeTitle(TrackMetadata metadata) {
		return metadata == null ? "bài hát hiện tại" : metadata.title();
	}

	private String resolveHudTitle(TrackMetadata metadata) {
		if (metadata != null) {
			return metadata.title();
		}

		return switch (phase) {
			case RESOLVING -> "Đang chuẩn bị bài hát";
			case DOWNLOADING -> "Đang tải dữ liệu phát";
			case PAUSED, PLAYING -> "Bài hát hiện tại";
			case ERROR -> "Lần phát gần nhất gặp lỗi";
			case IDLE -> "Chưa phát bài nào";
		};
	}

	private String resolvePlaylistEntryLabel(String rawUrl, int index) {
		if (index == playlistIndex && currentTrack != null) {
			return currentTrack.title();
		}

		if (rawUrl.length() <= 48) {
			return rawUrl;
		}

		return rawUrl.substring(0, 45) + "...";
	}

	private String summarizeError(Exception exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			return "Đã xảy ra lỗi không rõ nguyên nhân. Hãy xem log để biết thêm chi tiết.";
		}

		String lowered = message.toLowerCase(Locale.ROOT);
		if (lowered.contains("yt-dlp-ejs") || lowered.contains("js runtime")) {
			return "yt-dlp đang thiếu JavaScript runtime cho YouTube. Hãy cài Node.js mới rồi thử lại.";
		}

		return message;
	}

	private String summarizeThrowable(Throwable throwable) {
		if (throwable instanceof Exception exception) {
			return summarizeError(exception);
		}

		String message = throwable.getMessage();
		if (message == null || message.isBlank()) {
			return "Đã xảy ra lỗi không rõ nguyên nhân. Hãy xem log để biết thêm chi tiết.";
		}

		return message;
	}

	private String buildInstallationMessage(InstallationReport report) {
		StringBuilder builder = new StringBuilder("Đã tải xong công cụ vào ");
		builder.append(report.toolsDir());
		if (!report.installedTools().isEmpty()) {
			builder.append(". Có sẵn: ").append(String.join(", ", report.installedTools()));
		}
		if (!report.skippedTools().isEmpty()) {
			builder.append(". Bỏ qua: ").append(String.join(", ", report.skippedTools()));
		}
		builder.append(". Bạn có thể chạy lại lệnh /ytmusic play.");
		return builder.toString();
	}

	private String normalizeAndValidateUrl(String rawUrl) {
		String url = rawUrl == null ? "" : rawUrl.trim();
		if (!isValidHttpUrl(url)) {
			notifyError("Link không hợp lệ. Hãy dùng URL bắt đầu bằng http:// hoặc https://");
			return null;
		}
		return url;
	}

	private List<String> parseInputUrls(String rawInput) {
		String trimmed = rawInput == null ? "" : rawInput.trim();
		if (trimmed.isBlank()) {
			notifyError("Thiếu link. Hãy dùng /ytmusic play <link> hoặc /ytmusic play <link1> <link2> ...");
			return List.of();
		}

		String[] parts = trimmed.split("\\s+");
		if (parts.length > MAX_BATCH_LINKS) {
			notifyError("Mỗi lần chỉ nhận tối đa " + MAX_BATCH_LINKS + " link để tránh quá tải.");
			return List.of();
		}

		List<String> urls = new ArrayList<>(parts.length);
		for (String part : parts) {
			String normalizedUrl = normalizeAndValidateUrl(part);
			if (normalizedUrl == null) {
				return List.of();
			}
			urls.add(normalizedUrl);
		}

		return urls;
	}

	private List<String> normalizeUrlList(List<String> rawUrls) {
		if (rawUrls == null || rawUrls.isEmpty()) {
			notifyError("Danh sách link đang trống.");
			return List.of();
		}

		if (rawUrls.size() > MAX_BATCH_LINKS) {
			notifyError("Code playlist có " + rawUrls.size() + " link. Tối đa " + MAX_BATCH_LINKS + " link mỗi lần.");
			return List.of();
		}

		List<String> normalizedUrls = new ArrayList<>(rawUrls.size());
		for (String rawUrl : rawUrls) {
			String normalized = normalizeAndValidateUrl(rawUrl);
			if (normalized == null) {
				return List.of();
			}
			normalizedUrls.add(normalized);
		}

		return List.copyOf(normalizedUrls);
	}

	private void clearQueue() {
		queuedUrls.clear();
	}

	private boolean isValidHttpUrl(String url) {
		try {
			URI uri = new URI(url);
			return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
		} catch (URISyntaxException exception) {
			return false;
		}
	}

	private void notifyInfo(String message) {
		lastInfoMessage = message;
		sendMessage(Text.literal(PREFIX).formatted(Formatting.GOLD).append(Text.literal(message).formatted(Formatting.WHITE)));
	}

	private void notifyError(String message) {
		lastInfoMessage = message;
		sendMessage(Text.literal(PREFIX).formatted(Formatting.RED).append(Text.literal(message).formatted(Formatting.RED)));
	}

	private void sendMessage(MutableText message) {
		MinecraftClient client = clientSupplier.get();
		if (client == null) {
			return;
		}

		client.execute(() -> {
			if (client.player != null) {
				client.player.sendMessage(message, false);
			}
		});
	}

	public record HudSnapshot(
		boolean visible,
		PlaybackPhase phase,
		String title,
		String timelineText,
		float progressFraction,
		boolean determinate,
		int volumePercent,
		boolean loopEnabled
	) {
	}

	public record PlaylistSnapshot(
		List<PlaylistEntry> entries,
		int currentIndex,
		boolean loopEnabled
	) {
	}

	public record PlaylistEntry(
		int slot,
		String label,
		PlaylistEntryState state
	) {
	}

	public enum PlaylistEntryState {
		WAITING,
		RESOLVING,
		DOWNLOADING,
		PLAYING,
		PAUSED,
		DONE,
		ERROR
	}

	private PlaylistEntryState resolvePlaylistEntryState(int index) {
		if (playlistIndex < 0) {
			return PlaylistEntryState.WAITING;
		}

		if (index < playlistIndex) {
			return PlaylistEntryState.DONE;
		}

		if (index > playlistIndex) {
			return PlaylistEntryState.WAITING;
		}

		return switch (phase) {
			case RESOLVING -> PlaylistEntryState.RESOLVING;
			case DOWNLOADING -> PlaylistEntryState.DOWNLOADING;
			case PLAYING -> PlaylistEntryState.PLAYING;
			case PAUSED -> PlaylistEntryState.PAUSED;
			case ERROR -> PlaylistEntryState.ERROR;
			case IDLE -> PlaylistEntryState.DONE;
		};
	}
}
