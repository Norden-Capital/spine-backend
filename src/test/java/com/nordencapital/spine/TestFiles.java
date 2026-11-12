package com.nordencapital.spine;

import java.nio.charset.StandardCharsets;

/**
 * Tiny files with the right first bytes ("magic bytes") for each allowed type.
 */
final class TestFiles {

	static final byte[] JPEG = withTail(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 });

	static final byte[] PNG = withTail(
			new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });

	static final byte[] PDF = ascii("%PDF-1.7\n% test receipt\n");

	static final byte[] WEBP = withTail(concat(ascii("RIFF"), new byte[] { 0x10, 0, 0, 0 }, ascii("WEBPVP8 ")));

	static final byte[] HEIC = withTail(concat(new byte[] { 0, 0, 0, 0x18 }, ascii("ftypheic"), new byte[4]));

	static final byte[] HEIF = withTail(concat(new byte[] { 0, 0, 0, 0x18 }, ascii("ftypmif1"), new byte[4]));

	static final byte[] TEXT = ascii("just some text, not a picture");

	static final byte[] SVG = ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");

	private TestFiles() {
	}

	private static byte[] ascii(String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] withTail(byte[] head) {
		return concat(head, ascii(" rest of the test file"));
	}

	private static byte[] concat(byte[]... parts) {
		int length = 0;
		for (byte[] part : parts) {
			length += part.length;
		}
		byte[] out = new byte[length];
		int at = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, out, at, part.length);
			at += part.length;
		}
		return out;
	}

}
