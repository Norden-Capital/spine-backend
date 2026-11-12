package com.nordencapital.spine.common;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

/**
 * The optional "website" of a transaction. People type "claude.ai",
 * "https://www.google.com/x" and so on; we keep only the bare domain: lowercase, no
 * scheme, no "www.", no port, no path.
 */
public final class Websites {

	/** Longest raw text we look at (the stored domain is at most 253). */
	public static final int MAX_INPUT = 2048;

	public static final int MAX_DOMAIN = 253;

	private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://");

	private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

	private static final Pattern TLD = Pattern.compile("[a-z][a-z0-9-]*[a-z0-9]|xn--[a-z0-9-]+");

	private Websites() {
	}

	/**
	 * @return the bare domain, or null when the input is null or blank
	 * @throws ApiException 400 {@code validation_error} when it is not a real domain
	 */
	public static String normalize(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String domain = domainOrNull(input);
		if (domain == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error",
					"website: must be a website like example.com");
		}
		return domain;
	}

	/**
	 * A website in a PATCH body: left out = keep; null or "" = remove; text = set.
	 * @param node the JSON value (Java null = left out)
	 * @throws ApiException 400 {@code validation_error}
	 */
	public static Patch patch(tools.jackson.databind.JsonNode node) {
		if (node == null) {
			return Patch.KEEP;
		}
		if (node.isNull()) {
			return new Patch(true, null);
		}
		if (!node.isString()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "website: must be text or null");
		}
		return new Patch(true, normalize(node.asString()));
	}

	/**
	 * @param given false = keep the old value
	 * @param domain the new bare domain, or null to remove it
	 */
	public record Patch(boolean given, String domain) {

		public static final Patch KEEP = new Patch(false, null);

	}

	/** Same as {@link #normalize} but returns null instead of throwing. */
	public static String domainOrNull(String input) {
		if (input == null || input.length() > MAX_INPUT) {
			return null;
		}
		String text = input.strip().toLowerCase(Locale.ROOT);
		text = SCHEME.matcher(text).replaceFirst("");
		if (text.startsWith("//")) {
			text = text.substring(2);
		}
		int end = indexOfAny(text, "/?#\\");
		if (end >= 0) {
			text = text.substring(0, end);
		}
		if (text.contains("@") || text.contains("[")) {
			return null; // user info or an IPv6 literal: not a website
		}
		int colon = text.indexOf(':');
		if (colon >= 0) {
			if (!text.substring(colon + 1).matches("[0-9]{1,5}")) {
				return null;
			}
			text = text.substring(0, colon);
		}
		if (text.endsWith(".")) {
			text = text.substring(0, text.length() - 1);
		}
		if (text.startsWith("www.") && text.indexOf('.', 4) > 0) {
			text = text.substring(4);
		}
		try {
			text = IDN.toASCII(text, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
		return isDomain(text) ? text : null;
	}

	/** A real domain shape: 2+ labels, letters/digits/hyphens, a non-numeric top level. */
	public static boolean isDomain(String text) {
		if (text == null || text.isEmpty() || text.length() > MAX_DOMAIN) {
			return false;
		}
		String[] labels = text.split("\\.", -1);
		if (labels.length < 2) {
			return false;
		}
		for (String label : labels) {
			if (!LABEL.matcher(label).matches()) {
				return false;
			}
		}
		return TLD.matcher(labels[labels.length - 1]).matches();
	}

	private static int indexOfAny(String text, String chars) {
		for (int i = 0; i < text.length(); i++) {
			if (chars.indexOf(text.charAt(i)) >= 0) {
				return i;
			}
		}
		return -1;
	}

}
