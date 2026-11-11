package com.nordencapital.spine.mail;

/**
 * The one email layout for Spine: logo, title, one sentence, one black button, footer.
 * Email-safe HTML (tables + inline styles, no SVG, no external CSS) plus a plain-text
 * version. Reuse it for every new email.
 * <p>
 * The logo is a hosted PNG (white rounded card with the Lucide "wallet-cards" icon, 96x96 shown
 * at 48x48), because inline SVG does not show in Gmail or Outlook. The frontend serves it at
 * {@link #LOGO_PATH}. Its alt text is "Spine", shown when images are blocked.
 */
public final class EmailLayout {

	/** Path of the logo PNG on the frontend (file: public/email/spine-logo.png). */
	public static final String LOGO_PATH = "/email/spine-logo.png";

	private static final String FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

	private static final String FOOTER = "If you didn't expect this email, you can safely ignore it.";

	private static final String COMPANY = "Norden Capital";

	private EmailLayout() {
	}

	/**
	 * An email with one action button.
	 * @param preheader short preview text shown in the inbox list
	 * @param title bold title, e.g. "You're invited to Spine"
	 * @param action what the button does, e.g. "set your password"
	 * @param expiresIn e.g. "3 days"
	 * @param buttonText e.g. "Set password"
	 * @param link the button link
	 */
	public record ActionEmail(String preheader, String title, String action, String expiresIn, String buttonText,
			String link) {
	}

	/**
	 * @param logoUrl full URL of the logo PNG, e.g. frontend URL + {@link #LOGO_PATH}
	 */
	public static String html(ActionEmail email, String logoUrl) {
		String sentence = "Click the button below to " + email.action() + ". This link expires in "
				+ email.expiresIn().replace(' ', '\u00a0') + "."; // no-break space: "3 days" stays on one line
		String link = escape(email.link());
		return """
				<!doctype html>
				<html lang="en">
				<head>
				<meta charset="utf-8">
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<meta name="color-scheme" content="light only">
				<title>%1$s</title>
				</head>
				<body style="margin:0; padding:0; background-color:#ffffff;">
				<div style="display:none; max-height:0; overflow:hidden; opacity:0; mso-hide:all;">%2$s</div>
				<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" bgcolor="#ffffff" style="background-color:#ffffff;">
				<tr><td align="center" style="padding:56px 16px;">
				<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;">
				<tr><td align="center" style="padding-bottom:36px;"><img src="%9$s" width="48" height="48" alt="Spine" style="display:block; width:48px; height:48px; border:0; outline:none; text-decoration:none; font-family:%3$s; font-size:16px; font-weight:600; color:#111111;"></td></tr>
				<tr><td align="center" style="padding-bottom:12px; font-family:%3$s; font-size:24px; line-height:32px; font-weight:700; color:#111111;">%1$s</td></tr>
				<tr><td align="center" style="padding-bottom:32px; font-family:%3$s; font-size:16px; line-height:24px; color:#52525b;">%4$s</td></tr>
				<tr><td align="center" style="padding-bottom:40px;">
				<table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
				<td align="center" bgcolor="#111111" style="background-color:#111111; border-radius:8px;">
				<a href="%5$s" target="_blank" style="display:inline-block; padding:12px 28px; font-family:%3$s; font-size:16px; line-height:24px; font-weight:700; color:#ffffff; text-decoration:none; border-radius:8px;">%6$s</a>
				</td>
				</tr></table>
				</td></tr>
				<tr><td align="center" style="padding-bottom:32px; font-family:%3$s; font-size:12px; line-height:18px; color:#a1a1aa;">Button not working? Use this link:<br><a href="%5$s" target="_blank" style="color:#71717a; white-space:nowrap;">%10$s</a></td></tr>
				<tr><td align="center" style="border-top:1px solid #e4e4e7; padding-top:24px; font-family:%3$s; font-size:12px; line-height:18px; color:#a1a1aa;">%7$s<br>%8$s</td></tr>
				</table>
				</td></tr>
				</table>
				</body>
				</html>
				""".formatted(escape(email.title()), escape(email.preheader()), FONT, escape(sentence), link,
				escape(email.buttonText()), escape(FOOTER), escape(COMPANY), escape(logoUrl),
				escape(shortLink(email.link())));
	}

	/**
	 * What the fallback link shows: host + path, without "https://" and without the token
	 * ("spine-frontend-ten.vercel.app/set-password"). The href keeps the full URL.
	 */
	static String shortLink(String link) {
		String shown = link.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
		int end = shown.length();
		for (char c : new char[] { '?', '#' }) {
			int i = shown.indexOf(c);
			if (i >= 0 && i < end) {
				end = i;
			}
		}
		return shown.substring(0, end);
	}

	public static String text(ActionEmail email) {
		return """
				%s

				Open the link below to %s. This link expires in %s.

				%s: %s

				%s
				%s
				""".formatted(email.title(), email.action(), email.expiresIn(), email.buttonText(), email.link(),
				FOOTER, COMPANY);
	}

	static String escape(String value) {
		StringBuilder out = new StringBuilder(value.length());
		for (char c : value.toCharArray()) {
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&#39;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

}
