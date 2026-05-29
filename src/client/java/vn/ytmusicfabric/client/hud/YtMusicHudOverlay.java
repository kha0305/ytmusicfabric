package vn.ytmusicfabric.client.hud;

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import vn.ytmusicfabric.client.YtMusicController;
import vn.ytmusicfabric.client.YtMusicController.HudSnapshot;
import vn.ytmusicfabric.client.playback.PlaybackPhase;

public final class YtMusicHudOverlay {
	private static final int PANEL_WIDTH = 198;
	private static final int BAR_HEIGHT = 8;
	private static final int PADDING = 7;
	private static final int BOTTOM_PADDING = 10;
	private static final int TITLE_LINE_LIMIT = 2;

	private YtMusicHudOverlay() {
	}

	public static void register(YtMusicController controller) {
		HudRenderCallback.EVENT.register((context, tickDelta) -> render(context, controller));
	}

	private static void render(DrawContext context, YtMusicController controller) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null || client.options.hudHidden) {
			return;
		}

		HudSnapshot snapshot = controller.getHudSnapshot();
		if (!snapshot.visible()) {
			return;
		}

		TextRenderer textRenderer = client.textRenderer;
		int fontHeight = textRenderer.fontHeight;
		int contentWidth = PANEL_WIDTH - PADDING * 2;
		List<OrderedText> titleLines = textRenderer.wrapLines(Text.literal("Bài: " + snapshot.title()), contentWidth);
		if (titleLines.size() > TITLE_LINE_LIMIT) {
			titleLines = titleLines.subList(0, TITLE_LINE_LIMIT);
		}

		String statusLine = "Trạng thái: " + describePhase(snapshot.phase());
		String volumeLine = "Âm lượng: " + snapshot.volumePercent() + "% | Lặp: " + (snapshot.loopEnabled() ? "Bật" : "Tắt");
		int titleBlockHeight = titleLines.size() * 10;
		int x = context.getScaledWindowWidth() - PANEL_WIDTH - 12;
		int y = 18;
		int panelHeight = 24 + titleBlockHeight + 28 + BAR_HEIGHT + 6 + fontHeight + BOTTOM_PADDING;

		context.fill(x - 2, y - 2, x + PANEL_WIDTH + 2, y + panelHeight + 2, 0xA0080A0F);
		context.fill(x, y, x + PANEL_WIDTH, y + panelHeight, 0xD0161A21);
		context.fill(x, y, x + PANEL_WIDTH, y + 17, 0xE089620C);
		context.fill(x, y + 17, x + PANEL_WIDTH, y + 18, 0xE0F4D06F);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("YT NHẠC"), x + PANEL_WIDTH / 2, y + 5, 0xFFFDF4D7);

		int lineY = y + 24;
		for (OrderedText titleLine : titleLines) {
			context.drawTextWithShadow(textRenderer, titleLine, x + PADDING, lineY, 0xFFF5F7FA);
			lineY += 10;
		}

		context.drawTextWithShadow(textRenderer, Text.literal(statusLine), x + PADDING, lineY + 2, resolvePhaseColor(snapshot.phase()));
		context.drawTextWithShadow(textRenderer, Text.literal(snapshot.timelineText()), x + PADDING, lineY + 14, 0xFFE2E8F0);

		int barY = lineY + 28;
		drawProgressBar(context, snapshot, x + PADDING, barY, contentWidth);
		int volumeTextY = barY + BAR_HEIGHT + 6;
		context.drawTextWithShadow(textRenderer, Text.literal(volumeLine), x + PADDING, volumeTextY, 0xFFC9D3DF);
	}

	private static void drawProgressBar(DrawContext context, HudSnapshot snapshot, int x, int y, int width) {
		context.fill(x, y, x + width, y + BAR_HEIGHT, 0xB0252B35);
		context.fill(x, y, x + width, y + 1, 0x90F4D06F);
		context.fill(x, y + BAR_HEIGHT - 1, x + width, y + BAR_HEIGHT, 0x90000000);

		int fillWidth;
		if (snapshot.determinate()) {
			fillWidth = Math.max(0, Math.min(width, Math.round(width * snapshot.progressFraction())));
		} else {
			float phase = (System.nanoTime() / 1_000_000_000.0f) * 1.7f;
			float pulse = (float) ((Math.sin(phase) + 1.0) * 0.5);
			fillWidth = Math.max(18, Math.round((width - 18) * pulse) + 18);
		}

		if (fillWidth <= 0) {
			return;
		}

		context.fill(x, y, x + fillWidth, y + BAR_HEIGHT, resolvePhaseBarColor(snapshot.phase()));
	}

	private static String describePhase(PlaybackPhase phase) {
		return switch (phase) {
			case IDLE -> "Đang rảnh";
			case RESOLVING -> "Đang phân tích";
			case DOWNLOADING -> "Đang tải";
			case PLAYING -> "Đang phát";
			case PAUSED -> "Tạm dừng";
			case ERROR -> "Có lỗi";
		};
	}

	private static int resolvePhaseColor(PlaybackPhase phase) {
		return switch (phase) {
			case PLAYING -> 0xFF8AE18F;
			case PAUSED -> 0xFFFCD34D;
			case RESOLVING -> 0xFF93C5FD;
			case DOWNLOADING -> 0xFF67E8F9;
			case ERROR -> 0xFFFCA5A5;
			case IDLE -> 0xFFE5E7EB;
		};
	}

	private static int resolvePhaseBarColor(PlaybackPhase phase) {
		return switch (phase) {
			case PLAYING -> 0xFF22C55E;
			case PAUSED -> 0xFFEAB308;
			case RESOLVING -> 0xFF3B82F6;
			case DOWNLOADING -> 0xFF06B6D4;
			case ERROR -> 0xFFEF4444;
			case IDLE -> 0xFF64748B;
		};
	}
}
