package vn.ytmusicfabric.client.playlist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class PlaylistShareCodec {
	private static final String CODE_PREFIX = "ytmf1:";

	private PlaylistShareCodec() {
	}

	public static String encode(List<String> urls) {
		if (urls == null || urls.isEmpty()) {
			throw new IllegalArgumentException("Danh sach URL rong.");
		}

		String payload = String.join("\n", urls);
		String base64 = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		return CODE_PREFIX + base64;
	}

	public static List<String> decode(String rawCode) {
		String code = rawCode == null ? "" : rawCode.trim();
		if (code.isBlank()) {
			throw new IllegalArgumentException("Code playlist dang rong.");
		}

		if (!code.startsWith(CODE_PREFIX)) {
			throw new IllegalArgumentException("Code playlist khong dung dinh dang ytmf1.");
		}

		String encoded = code.substring(CODE_PREFIX.length()).trim();
		if (encoded.isBlank()) {
			throw new IllegalArgumentException("Code playlist khong co du lieu.");
		}

		byte[] decodedBytes;
		try {
			decodedBytes = Base64.getUrlDecoder().decode(encoded);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Code playlist khong giai ma duoc.", exception);
		}

		String payload = new String(decodedBytes, StandardCharsets.UTF_8);
		String[] parts = payload.split("\\R+");
		List<String> urls = new ArrayList<>(parts.length);
		for (String part : parts) {
			String url = part.trim();
			if (!url.isBlank()) {
				urls.add(url);
			}
		}

		if (urls.isEmpty()) {
			throw new IllegalArgumentException("Code playlist khong chua link hop le.");
		}

		return List.copyOf(urls);
	}
}
