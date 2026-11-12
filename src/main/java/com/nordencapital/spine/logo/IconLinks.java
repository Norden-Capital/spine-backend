package com.nordencapital.spine.logo;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds icon links in a home page: {@code <link rel="apple-touch-icon">} and
 * {@code <link rel="icon">}, biggest declared size first (SVG ones are checked when downloaded).
 */
final class IconLinks {

	private static final Pattern LINK = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);

	private static final Pattern ATTR = Pattern
		.compile("([a-zA-Z_:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");

	private static final Pattern SIZE = Pattern.compile("(\\d{1,4})[xX](\\d{1,4})");

	/** Apple touch icons without a size are usually 180x180. */
	private static final int APPLE_DEFAULT = 180;

	private IconLinks() {
	}

	/** An icon link found in the page. */
	record Candidate(URI uri, int declaredSize) {
	}

	static List<Candidate> parse(String html, URI base) {
		List<Candidate> found = new ArrayList<>();
		Matcher links = LINK.matcher(html);
		while (links.find()) {
			String rel = null;
			String href = null;
			String sizes = null;
			String type = null;
			Matcher attrs = ATTR.matcher(links.group());
			while (attrs.find()) {
				String value = (attrs.group(2) != null) ? attrs.group(2)
						: (attrs.group(3) != null) ? attrs.group(3) : attrs.group(4);
				switch (attrs.group(1).toLowerCase(Locale.ROOT)) {
					case "rel" -> rel = value.toLowerCase(Locale.ROOT);
					case "href" -> href = value.strip().replace("&amp;", "&");
					case "sizes" -> sizes = value;
					case "type" -> type = value.toLowerCase(Locale.ROOT);
					default -> {
					}
				}
			}
			if (rel == null || href == null || href.isEmpty()) {
				continue;
			}
			List<String> rels = List.of(rel.split("\\s+"));
			boolean apple = rels.stream().anyMatch((r) -> r.startsWith("apple-touch-icon"));
			if (!apple && !rels.contains("icon")) {
				continue;
			}
			if (href.startsWith("data:") || rels.contains("mask-icon")) {
				continue;
			}
			URI uri;
			try {
				uri = base.resolve(href.replace(" ", "%20"));
			}
			catch (IllegalArgumentException ex) {
				continue;
			}
			String scheme = uri.getScheme();
			if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
				continue;
			}
			int size = biggest(sizes);
			found.add(new Candidate(uri, (size > 0) ? size : (apple ? APPLE_DEFAULT : 0)));
		}
		found.sort(Comparator.comparingInt(Candidate::declaredSize).reversed());
		return found;
	}

	private static final Pattern MANIFEST_LINK = Pattern.compile("<link\\b[^>]*\\brel\\s*=\\s*[\"']?manifest[^>]*>",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern HREF = Pattern.compile("\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern MANIFEST_ICON = Pattern.compile("\\{[^{}]*\"src\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}");

	private static final Pattern MANIFEST_SIZES = Pattern.compile("\"sizes\"\\s*:\\s*\"([^\"]*)\"");

	/** The web app manifest of the page, if it has one. */
	static URI manifest(String html, URI base) {
		Matcher link = MANIFEST_LINK.matcher(html);
		if (!link.find()) {
			return null;
		}
		Matcher href = HREF.matcher(link.group());
		if (!href.find()) {
			return null;
		}
		String value = (href.group(1) != null) ? href.group(1) : (href.group(2) != null) ? href.group(2) : href.group(3);
		try {
			URI uri = base.resolve(value.strip().replace("&amp;", "&").replace(" ", "%20"));
			String scheme = uri.getScheme();
			return (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) ? uri : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** Icons listed in a manifest JSON, biggest declared size first. */
	static List<Candidate> manifestIcons(String json, URI manifestUri) {
		List<Candidate> found = new ArrayList<>();
		Matcher icon = MANIFEST_ICON.matcher(json);
		while (icon.find()) {
			String src = icon.group(1).replace("\\/", "/");
			if (src.toLowerCase(Locale.ROOT).split("[?#]")[0].endsWith(".svg") || src.startsWith("data:")) {
				continue;
			}
			Matcher sizes = MANIFEST_SIZES.matcher(icon.group());
			int size = sizes.find() ? biggest(sizes.group(1)) : 0;
			try {
				URI uri = manifestUri.resolve(src.replace(" ", "%20"));
				String scheme = uri.getScheme();
				if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
					found.add(new Candidate(uri, size));
				}
			}
			catch (IllegalArgumentException ex) {
				// skip a bad src
			}
		}
		found.sort(Comparator.comparingInt(Candidate::declaredSize).reversed());
		return found;
	}

	private static int biggest(String sizes) {
		if (sizes == null) {
			return 0;
		}
		int best = 0;
		Matcher matcher = SIZE.matcher(sizes);
		while (matcher.find()) {
			best = Math.max(best, Math.min(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
		}
		return best;
	}

}
