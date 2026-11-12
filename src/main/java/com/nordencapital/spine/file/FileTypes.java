package com.nordencapital.spine.file;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the real file type from the first bytes ("magic bytes"). What the browser says
 * the type is does not count.
 */
public final class FileTypes {

	/** How many first bytes {@link #detect} needs. */
	public static final int HEAD_SIZE = 16;

	private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis");

	private static final Set<String> HEIF_BRANDS = Set.of("mif1", "msf1", "heif");

	private FileTypes() {
	}

	/**
	 * @return image/jpeg, image/png, image/webp, image/heic, image/heif or
	 * application/pdf; empty for anything else
	 */
	public static Optional<String> detect(byte[] head) {
		if (startsWith(head, 0, 0xFF, 0xD8, 0xFF)) {
			return Optional.of("image/jpeg");
		}
		if (startsWith(head, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
			return Optional.of("image/png");
		}
		if (ascii(head, 0, 5).equals("%PDF-")) {
			return Optional.of("application/pdf");
		}
		if (ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP")) {
			return Optional.of("image/webp");
		}
		if (ascii(head, 4, 4).equals("ftyp")) {
			String brand = ascii(head, 8, 4);
			if (HEIC_BRANDS.contains(brand)) {
				return Optional.of("image/heic");
			}
			if (HEIF_BRANDS.contains(brand)) {
				return Optional.of("image/heif");
			}
		}
		return Optional.empty();
	}

	private static boolean startsWith(byte[] head, int offset, int... expected) {
		if (head.length < offset + expected.length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if ((head[offset + i] & 0xFF) != expected[i]) {
				return false;
			}
		}
		return true;
	}

	private static String ascii(byte[] head, int offset, int length) {
		if (head.length < offset + length) {
			return "";
		}
		return new String(Arrays.copyOfRange(head, offset, offset + length), StandardCharsets.ISO_8859_1);
	}

}
