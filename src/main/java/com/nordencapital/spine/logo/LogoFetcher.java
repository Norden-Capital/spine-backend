package com.nordencapital.spine.logo;

/**
 * Reads a website's home page to find the icon it declares. The image itself is never
 * downloaded by the server: the browser loads it.
 */
public interface LogoFetcher {

	/**
	 * @param domain a bare, normalized domain like "claude.ai"
	 */
	Lookup lookUp(String domain);

	/**
	 * @param pageRead true if the home page could be read
	 * @param iconUrl the best icon the page declares (absolute http/https URL), or null
	 * @param reason why there is no icon URL (for the log; no user data), or null
	 */
	record Lookup(boolean pageRead, String iconUrl, String reason) {
	}

}
