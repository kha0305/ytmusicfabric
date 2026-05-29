package vn.ytmusicfabric.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import vn.ytmusicfabric.client.YtMusicController;
import vn.ytmusicfabric.client.YtMusicController.PlaylistEntry;
import vn.ytmusicfabric.client.YtMusicController.PlaylistSnapshot;

public class YtMusicScreen extends Screen {
	private static final int PANEL_WIDTH = 392;
	private static final int PANEL_HEIGHT = 436;
	private static final int LINK_FIELD_COUNT = 5;
	private static final int LIST_ROW_HEIGHT = 20;

	private final YtMusicController controller;
	private final AtomicLong toolStatusRequestCounter = new AtomicLong();
	private final List<TextFieldWidget> urlFields = new ArrayList<>();

	private VolumeSliderWidget volumeSlider;
	private ButtonWidget loopButton;
	private ButtonWidget hudButton;
	private String toolStatusText = "Đang chờ kiểm tra công cụ...";

	public YtMusicScreen(YtMusicController controller) {
		super(Text.literal("YT Music Fabric"));
		this.controller = controller;
	}

	@Override
	protected void init() {
		super.init();
		urlFields.clear();

		int panelLeft = (this.width - PANEL_WIDTH) / 2;
		int panelRight = panelLeft + PANEL_WIDTH;
		int top = 18;
		int innerLeft = panelLeft + 16;
		int inputWidth = PANEL_WIDTH - 32;

		hudButton = addDrawableChild(ButtonWidget.builder(buildHudButtonLabel(), button -> controller.toggleHudVisibility())
			.dimensions(panelRight - 102, top + 4, 88, 20)
			.build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Copy mã"), button -> copyPlaylistCodeToClipboard())
			.dimensions(panelRight - 244, top + 34, 72, 20)
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Dán mã"), button -> pastePlaylistCodeFromClipboard())
			.dimensions(panelRight - 166, top + 34, 72, 20)
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Dán link"), button -> pasteFromClipboard())
			.dimensions(panelRight - 88, top + 34, 72, 20)
			.build());

		for (int index = 0; index < LINK_FIELD_COUNT; index++) {
			int rowY = top + 58 + (index * 22);
			TextFieldWidget field = new TextFieldWidget(
				this.textRenderer,
				innerLeft,
				rowY,
				inputWidth,
				18,
				Text.literal("Link " + (index + 1))
			);
			field.setMaxLength(2048);
			field.setPlaceholder(Text.literal("Link " + (index + 1) + " (YouTube URL)"));
			field.setEditableColor(0xFFF8FAFC);
			field.setUneditableColor(0xFF94A3B8);
			urlFields.add(field);
			addDrawableChild(field);
		}
		if (!urlFields.isEmpty()) {
			TextFieldWidget firstField = urlFields.get(0);
			setInitialFocus(firstField);
			setFocused(firstField);
			firstField.setFocused(true);
			moveCaretToEnd(firstField);
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("Phát"), button -> playFromField())
			.dimensions(innerLeft, top + 174, 76, 20)
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Tạm dừng"), button -> controller.pause())
			.dimensions(innerLeft + 92, top + 174, 84, 20)
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Tiếp tục"), button -> controller.resume())
			.dimensions(innerLeft + 184, top + 174, 84, 20)
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Dừng"), button -> controller.stopWithFeedback())
			.dimensions(innerLeft + 276, top + 174, 84, 20)
			.build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Cài yt-dlp"), button -> {
			controller.installTools(true, false);
			toolStatusText = "Đang tải yt-dlp...";
		}).dimensions(innerLeft, top + 200, 110, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cài ffmpeg"), button -> {
			controller.installTools(false, true);
			toolStatusText = "Đang tải ffmpeg...";
		}).dimensions(innerLeft + 118, top + 200, 110, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cài tất cả"), button -> {
			controller.installTools(true, true);
			toolStatusText = "Đang tải bộ công cụ...";
		}).dimensions(innerLeft + 236, top + 200, 124, 20).build());

		volumeSlider = addDrawableChild(new VolumeSliderWidget(
			innerLeft,
			top + 236,
			inputWidth,
			20,
			controller,
			controller.getConfiguredVolumePercent()
		));
		addDrawableChild(ButtonWidget.builder(Text.literal("Công cụ"), button -> {
			refreshToolStatusAsync(true);
		}).dimensions(innerLeft, top + 268, 76, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Reload"), button -> {
			toolStatusText = "Đang reload mềm tài nguyên...";
			controller.softReloadClient();
			refreshToolStatusAsync(false);
		})
			.dimensions(innerLeft + 92, top + 268, 84, 20)
			.build());
		loopButton = addDrawableChild(ButtonWidget.builder(buildLoopButtonLabel(), button -> controller.toggleLoop())
			.dimensions(innerLeft + 184, top + 268, 84, 20)
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Xoá DS"), button -> controller.clearUpcomingPlaylist())
			.dimensions(innerLeft + 276, top + 268, 84, 20)
			.build());

		toolStatusText = "Đang kiểm tra công cụ ở nền...";
		refreshToolStatusAsync(false);
	}

	@Override
	public void tick() {
		super.tick();
		if (volumeSlider != null) {
			volumeSlider.syncFromController();
			volumeSlider.flushPendingCommit();
		}
		if (loopButton != null) {
			loopButton.setMessage(buildLoopButtonLabel());
		}
		if (hudButton != null) {
			hudButton.setMessage(buildHudButtonLabel());
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		int panelLeft = (this.width - PANEL_WIDTH) / 2;
		int top = 18;
		int panelRight = panelLeft + PANEL_WIDTH;
		int panelBottom = top + PANEL_HEIGHT;
		int innerLeft = panelLeft + 16;
		int listTop = top + 318;
		int listBottom = panelBottom - 12;
		int copyCodeButtonLeft = panelRight - 244;

		context.fill(0, 0, this.width, this.height, 0x58060B14);
		context.fill(panelLeft - 10, top - 10, panelRight + 10, panelBottom + 10, 0x78101724);
		context.fill(panelLeft, top, panelRight, panelBottom, 0xF02A313D);
		context.fill(panelLeft, top, panelRight, top + 28, 0xFF35455C);
		context.fill(panelLeft, top + 29, panelRight, top + 30, 0xFF92A4BD);
		context.fill(innerLeft, top + 34, panelRight - 16, top + 166, 0x90202631);
		context.fill(innerLeft, top + 168, panelRight - 16, top + 226, 0x90202631);
		context.fill(innerLeft, top + 228, panelRight - 16, top + 260, 0x90202631);
		context.fill(innerLeft, top + 262, panelRight - 16, top + 294, 0x90202631);
		context.fill(innerLeft, listTop, panelRight - 16, listBottom, 0xA0181F29);
		context.fill(panelLeft, top, panelRight, top + 1, 0xFFD6DEE8);
		context.fill(panelLeft, panelBottom - 1, panelRight, panelBottom, 0xFF5C6A7F);
		context.fill(panelLeft, top, panelLeft + 1, panelBottom, 0xFFD6DEE8);
		context.fill(panelRight - 1, top, panelRight, panelBottom, 0xFF5C6A7F);

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, top + 9, 0xFFF8FAFC);
		context.drawTextWithShadow(this.textRenderer, Text.literal("Danh sách link"), innerLeft, top + 40, 0xFFF8FAFC);
		int hintX = innerLeft + 92;
		int hintMaxWidth = Math.max(24, copyCodeButtonLeft - hintX - 8);
		context.drawTextWithShadow(
			this.textRenderer,
			Text.literal(trimToWidth("Tối đa 5 link theo thứ tự.", hintMaxWidth)),
			hintX,
			top + 40,
			0xFFC7D2E1
		);

		super.render(context, mouseX, mouseY, delta);

		context.drawTextWithShadow(this.textRenderer, Text.literal(trimToWidth(toolStatusText, PANEL_WIDTH - 32)), innerLeft, top + 224, 0xFFC7F9CC);
		context.drawTextWithShadow(this.textRenderer, Text.literal(trimToWidth(controller.describeStatusForUi(), PANEL_WIDTH - 32)), innerLeft, top + 300, 0xFFFDE68A);

		drawPlaylistPanel(context, innerLeft, panelRight - 16, listTop, listBottom);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private void playFromField() {
		if (urlFields.isEmpty()) {
			return;
		}

		List<String> urls = collectInputUrls();
		if (urls.isEmpty()) {
			controller.play("");
			return;
		}

		controller.play(String.join(" ", urls));
	}

	private void pasteFromClipboard() {
		MinecraftClient client = this.client != null ? this.client : MinecraftClient.getInstance();
		if (client == null || urlFields.isEmpty()) {
			return;
		}

		String clipboard = client.keyboard.getClipboard();
		if (clipboard == null) {
			clipboard = "";
		}

		String trimmed = clipboard.trim();
		if (trimmed.isBlank()) {
			fillUrlFields(List.of());
			TextFieldWidget firstField = urlFields.get(0);
			setFocused(firstField);
			firstField.setFocused(true);
			return;
		}

		String[] parts = trimmed.split("\\s+");
		int fillCount = Math.min(parts.length, LINK_FIELD_COUNT);
		List<String> urls = new ArrayList<>(fillCount);
		for (int index = 0; index < fillCount; index++) {
			urls.add(parts[index]);
		}

		fillUrlFields(urls);
	}

	private void pastePlaylistCodeFromClipboard() {
		MinecraftClient client = this.client != null ? this.client : MinecraftClient.getInstance();
		if (client == null || urlFields.isEmpty()) {
			return;
		}

		String clipboard = client.keyboard.getClipboard();
		if (clipboard == null || clipboard.trim().isBlank()) {
			controller.notifyUiError("Clipboard đang trống, chưa có playlist code để dán.");
			return;
		}

		List<String> urls = controller.parsePlaylistCode(clipboard);
		if (urls.isEmpty()) {
			return;
		}

		fillUrlFields(urls);
		controller.play(String.join(" ", urls));
		toolStatusText = "Đã dán mã playlist và bắt đầu phát.";
	}

	private void copyPlaylistCodeToClipboard() {
		MinecraftClient client = this.client != null ? this.client : MinecraftClient.getInstance();
		if (client == null) {
			return;
		}

		List<String> urls = collectInputUrls();
		String code = urls.isEmpty()
			? controller.createPlaylistCodeFromCurrentPlaylist()
			: controller.createPlaylistCodeFromUrls(urls);
		if (code == null || code.isBlank()) {
			return;
		}

		client.keyboard.setClipboard(code);
		toolStatusText = "Đã copy playlist code vào clipboard.";
		controller.notifyUiInfo("Đã copy playlist code. Người khác có thể dùng /ytmusic playlist code play <code> hoặc bấm Dán mã.");
	}

	private void fillUrlFields(List<String> urls) {
		for (TextFieldWidget field : urlFields) {
			field.setText("");
		}

		int fillCount = Math.min(urls.size(), LINK_FIELD_COUNT);
		for (int index = 0; index < fillCount; index++) {
			urlFields.get(index).setText(urls.get(index));
		}

		if (fillCount == 0) {
			TextFieldWidget firstField = urlFields.get(0);
			setFocused(firstField);
			firstField.setFocused(true);
			moveCaretToEnd(firstField);
			return;
		}

		TextFieldWidget targetField = urlFields.get(Math.max(0, fillCount - 1));
		setFocused(targetField);
		targetField.setFocused(true);
		moveCaretToEnd(targetField);
	}

	private void refreshToolStatusAsync(boolean announceInChat) {
		long requestId = toolStatusRequestCounter.incrementAndGet();
		toolStatusText = "Đang kiểm tra công cụ...";
		controller.refreshToolStatusAsync(announceInChat, status -> {
			if (requestId != toolStatusRequestCounter.get()) {
				return;
			}
			toolStatusText = status;
		});
	}

	private Text buildLoopButtonLabel() {
		return Text.literal(controller.isLoopEnabled() ? "Lặp: Bật" : "Lặp: Tắt");
	}

	private Text buildHudButtonLabel() {
		return Text.literal(controller.isHudVisible() ? "HUD: Bật" : "HUD: Tắt");
	}

	private void drawPlaylistPanel(DrawContext context, int left, int right, int top, int bottom) {
		context.drawTextWithShadow(this.textRenderer, Text.literal("Danh sách phát"), left + 8, top + 8, 0xFFF8FAFC);
		context.drawTextWithShadow(
			this.textRenderer,
			Text.literal(controller.isLoopEnabled() ? "Lặp: Bật" : "Lặp: Tắt"),
			right - 66,
			top + 8,
			controller.isLoopEnabled() ? 0xFF86EFAC : 0xFFCBD5E1
		);

		List<PlaylistRow> rows = buildPlaylistRows();
		int rowTop = top + 26;
		int logY = bottom - 16;
		int rowAreaBottom = logY - 6;
		int maxVisibleRows = Math.max(0, (rowAreaBottom - rowTop) / LIST_ROW_HEIGHT);

		if (rows.isEmpty()) {
			context.drawCenteredTextWithShadow(
				this.textRenderer,
				Text.literal("Chưa có link nào. Dán vào Link 1 đến Link 5 ở phía trên."),
				(left + right) / 2,
				top + 52,
				0xFF94A3B8
			);
		} else {
			int visibleRows = Math.min(rows.size(), maxVisibleRows);
			for (int index = 0; index < visibleRows; index++) {
				int currentTop = rowTop + (index * LIST_ROW_HEIGHT);
				PlaylistRow row = rows.get(index);
				context.fill(left + 6, currentTop, right - 6, currentTop + LIST_ROW_HEIGHT - 2, row.backgroundColor());
				context.drawTextWithShadow(this.textRenderer, Text.literal(row.slotLabel()), left + 12, currentTop + 6, 0xFFE2E8F0);
				int statusWidth = this.textRenderer.getWidth(row.stateLabel());
				int labelMaxWidth = Math.max(40, right - left - 38 - statusWidth - 26);
				context.drawTextWithShadow(
					this.textRenderer,
					Text.literal(trimToWidth(row.mainLabel(), labelMaxWidth)),
					left + 56,
					currentTop + 6,
					row.labelColor()
				);
				context.drawTextWithShadow(
					this.textRenderer,
					Text.literal(row.stateLabel()),
					right - statusWidth - 12,
					currentTop + 6,
					row.stateColor()
				);
			}

			int hiddenRows = rows.size() - visibleRows;
			if (hiddenRows > 0) {
				context.drawTextWithShadow(
					this.textRenderer,
					Text.literal("+" + hiddenRows + " mục nữa..."),
					left + 8,
					rowAreaBottom - 10,
					0xFF94A3B8
				);
			}
		}

		context.drawTextWithShadow(
			this.textRenderer,
			Text.literal(trimToWidth("Log: " + controller.getLastInfoMessage(), right - left - 16)),
			left + 8,
			logY,
			0xFFFDE68A
		);
	}

	private List<PlaylistRow> buildPlaylistRows() {
		PlaylistSnapshot snapshot = controller.getPlaylistSnapshot();
		if (!snapshot.entries().isEmpty()) {
			List<PlaylistRow> rows = new ArrayList<>(snapshot.entries().size());
			for (PlaylistEntry entry : snapshot.entries()) {
				rows.add(buildControllerPlaylistRow(entry));
			}
			return rows;
		}

		List<String> draftUrls = collectInputUrls();
		List<PlaylistRow> rows = new ArrayList<>(draftUrls.size());
		for (int index = 0; index < draftUrls.size(); index++) {
			rows.add(new PlaylistRow(
				"Link " + (index + 1),
				draftUrls.get(index),
				"Sẵn sàng",
				0xFFE2E8F0,
				0xFF93C5FD,
				0x66334155
			));
		}
		return rows;
	}

	private PlaylistRow buildControllerPlaylistRow(PlaylistEntry entry) {
		return switch (entry.state()) {
			case PLAYING -> new PlaylistRow("Link " + entry.slot(), entry.label(), "Đang phát", 0xFFDCFCE7, 0xFF4ADE80, 0x66335438);
			case PAUSED -> new PlaylistRow("Link " + entry.slot(), entry.label(), "Tạm dừng", 0xFFFEF3C7, 0xFFFBBF24, 0x6657331A);
			case RESOLVING -> new PlaylistRow("Link " + entry.slot(), entry.label(), "Phân tích", 0xFFDBEAFE, 0xFF60A5FA, 0x6620334A);
			case DOWNLOADING -> new PlaylistRow("Link " + entry.slot(), entry.label(), "Đang tải", 0xFFDBEAFE, 0xFF38BDF8, 0x661B3448);
			case DONE -> new PlaylistRow("Link " + entry.slot(), entry.label(), "Xong", 0xFFA8B3C7, 0xFF94A3B8, 0x66303946);
			case ERROR -> new PlaylistRow("Link " + entry.slot(), entry.label(), "Lỗi", 0xFFFECACA, 0xFFF87171, 0x66543232);
			case WAITING -> new PlaylistRow("Link " + entry.slot(), entry.label(), "Chờ", 0xFFE2E8F0, 0xFFCBD5E1, 0x66343C49);
		};
	}

	private List<String> collectInputUrls() {
		List<String> urls = new ArrayList<>(LINK_FIELD_COUNT);
		for (TextFieldWidget field : urlFields) {
			String value = field.getText().trim();
			if (!value.isBlank()) {
				urls.add(value);
			}
		}
		return urls;
	}

	private String trimToWidth(String text, int width) {
		String trimmed = this.textRenderer.trimToWidth(text, Math.max(24, width));
		return trimmed.length() < text.length() ? trimmed + "..." : trimmed;
	}

	private void moveCaretToEnd(TextFieldWidget field) {
		try {
			TextFieldWidget.class.getMethod("setCursorToEnd", boolean.class).invoke(field, false);
			return;
		} catch (ReflectiveOperationException ignored) {
		}
		try {
			TextFieldWidget.class.getMethod("setCursorToEnd").invoke(field);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	private static final class VolumeSliderWidget extends SliderWidget {
		private final YtMusicController controller;
		private int lastCommittedPercent;
		private boolean hasPendingCommit;
		private int ticksSinceLastChange;

		private VolumeSliderWidget(int x, int y, int width, int height, YtMusicController controller, int initialPercent) {
			super(x, y, width, height, Text.empty(), initialPercent / 100.0);
			this.controller = controller;
			this.lastCommittedPercent = clamp(initialPercent);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Text.literal("Âm lượng: " + getPercent() + "%"));
		}

		@Override
		protected void applyValue() {
			int percent = getPercent();
			controller.previewVolumePercent(percent);
			if (percent != lastCommittedPercent) {
				hasPendingCommit = true;
				ticksSinceLastChange = 0;
			}
			updateMessage();
		}

		private void syncFromController() {
			int currentPercent = clamp(controller.getConfiguredVolumePercent());
			if (currentPercent == lastCommittedPercent && currentPercent == getPercent()) {
				return;
			}

			this.value = currentPercent / 100.0;
			this.lastCommittedPercent = currentPercent;
			this.hasPendingCommit = false;
			this.ticksSinceLastChange = 0;
			updateMessage();
		}

		private void flushPendingCommit() {
			if (!hasPendingCommit) {
				return;
			}

			ticksSinceLastChange++;
			if (ticksSinceLastChange < 3) {
				return;
			}

			int percent = getPercent();
			controller.commitVolumePercent(percent, false);
			lastCommittedPercent = percent;
			hasPendingCommit = false;
			ticksSinceLastChange = 0;
			updateMessage();
		}

		private int getPercent() {
			return clamp((int) Math.round(this.value * 100.0));
		}

		private static int clamp(int value) {
			return Math.max(0, Math.min(100, value));
		}
	}

	private record PlaylistRow(
		String slotLabel,
		String mainLabel,
		String stateLabel,
		int labelColor,
		int stateColor,
		int backgroundColor
	) {
	}
}
