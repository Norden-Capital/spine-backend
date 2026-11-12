package com.nordencapital.spine.logo;

/**
 * Type and size of a logo image, read from its first bytes (magic bytes). PNG, JPEG,
 * WebP, ICO, and SVG only when it is a plain drawing (no scripts, handlers or outside links).
 * @param width 0 if unknown
 * @param height 0 if unknown
 */
record ImageInfo(String contentType, int width, int height) {

	/** @return the info, or null if the bytes are not an accepted image */
	static ImageInfo detect(byte[] d) {
		if (d == null || d.length < 12) {
			return null;
		}
		if (u8(d, 0) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G' && u8(d, 4) == 0x0d && u8(d, 5) == 0x0a
				&& u8(d, 6) == 0x1a && u8(d, 7) == 0x0a) {
			return (d.length >= 24) ? new ImageInfo("image/png", u32be(d, 16), u32be(d, 20)) : null;
		}
		if (u8(d, 0) == 0xff && u8(d, 1) == 0xd8 && u8(d, 2) == 0xff) {
			return jpeg(d);
		}
		if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B'
				&& d[11] == 'P') {
			return webp(d);
		}
		if (d[0] == 0 && d[1] == 0 && d[2] == 1 && d[3] == 0) {
			return ico(d);
		}
		return svg(d);
	}

	/** A vector image counts as a big square one. */
	static final int SVG_SIZE = 256;

	private static final java.util.regex.Pattern SVG_UNSAFE = java.util.regex.Pattern.compile(
			"<script|<foreignobject|<iframe|<embed|<object|<!entity|<!doctype|javascript:|data:text"
					+ "|\\son[a-z]+\\s*=|(xlink:)?href\\s*=\\s*[\"']\\s*(https?:|//)|url\\(\\s*['\"]?\\s*(https?:|//)"
					+ "|@import");

	/**
	 * SVG only when it is a plain drawing: no scripts, no event handlers, no outside links or
	 * embedded documents. Anything doubtful is refused (the next icon is tried).
	 */
	private static ImageInfo svg(byte[] d) {
		String text = new String(d, java.nio.charset.StandardCharsets.UTF_8).strip();
		if (text.startsWith("\uFEFF")) {
			text = text.substring(1);
		}
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		boolean looksSvg = lower.startsWith("<svg") || (lower.startsWith("<?xml") && lower.contains("<svg"));
		if (!looksSvg || !lower.contains("</svg>") || SVG_UNSAFE.matcher(lower).find()) {
			return null;
		}
		return new ImageInfo("image/svg+xml", SVG_SIZE, SVG_SIZE);
	}

	/** The smaller side (what matters for a square logo). */
	int size() {
		return Math.min(this.width, this.height);
	}

	/** Width and height differ by at most 10%. */
	boolean isSquare() {
		int max = Math.max(this.width, this.height);
		return max > 0 && Math.abs(this.width - this.height) * 10 <= max;
	}

	private static ImageInfo jpeg(byte[] d) {
		int i = 2;
		while (i + 9 < d.length) {
			if (u8(d, i) != 0xff) {
				i++;
				continue;
			}
			int marker = u8(d, i + 1);
			if (marker == 0xff) {
				i++;
				continue;
			}
			if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
				i += 2;
				continue;
			}
			boolean frame = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
			if (frame) {
				return new ImageInfo("image/jpeg", u16be(d, i + 7), u16be(d, i + 5));
			}
			i += 2 + u16be(d, i + 2);
		}
		return new ImageInfo("image/jpeg", 0, 0);
	}

	private static ImageInfo webp(byte[] d) {
		if (d.length < 30) {
			return null;
		}
		String chunk = new String(d, 12, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
		return switch (chunk) {
			case "VP8 " -> new ImageInfo("image/webp", u16le(d, 26) & 0x3fff, u16le(d, 28) & 0x3fff);
			case "VP8L" -> new ImageInfo("image/webp", 1 + (((u8(d, 22) & 0x3f) << 8) | u8(d, 21)),
					1 + (((u8(d, 24) & 0x0f) << 10) | (u8(d, 23) << 2) | ((u8(d, 22) & 0xc0) >> 6)));
			case "VP8X" -> new ImageInfo("image/webp", 1 + u24le(d, 24), 1 + u24le(d, 27));
			default -> null;
		};
	}

	/** An .ico holds several sizes: the biggest square one counts (0 means 256). */
	private static ImageInfo ico(byte[] d) {
		int count = u16le(d, 4);
		if (count < 1 || d.length < 6 + 16 * count) {
			return null;
		}
		int best = 0;
		for (int i = 0; i < count; i++) {
			int w = (u8(d, 6 + 16 * i) == 0) ? 256 : u8(d, 6 + 16 * i);
			int h = (u8(d, 7 + 16 * i) == 0) ? 256 : u8(d, 7 + 16 * i);
			best = Math.max(best, Math.min(w, h));
		}
		return new ImageInfo("image/x-icon", best, best);
	}

	private static int u8(byte[] d, int i) {
		return d[i] & 0xff;
	}

	private static int u16be(byte[] d, int i) {
		return (u8(d, i) << 8) | u8(d, i + 1);
	}

	private static int u16le(byte[] d, int i) {
		return u8(d, i) | (u8(d, i + 1) << 8);
	}

	private static int u24le(byte[] d, int i) {
		return u8(d, i) | (u8(d, i + 1) << 8) | (u8(d, i + 2) << 16);
	}

	private static int u32be(byte[] d, int i) {
		long value = ((long) u8(d, i) << 24) | (u8(d, i + 1) << 16) | (u8(d, i + 2) << 8) | u8(d, i + 3);
		return (int) Math.min(value, Integer.MAX_VALUE);
	}

}
