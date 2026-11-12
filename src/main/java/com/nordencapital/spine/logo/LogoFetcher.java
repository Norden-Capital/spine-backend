package com.nordencapital.spine.logo;

import java.util.Optional;

/**
 * Downloads the logo of a website domain.
 */
public interface LogoFetcher {

	/**
	 * @param domain a bare, normalized domain like "claude.ai"
	 * @return the best image found, or empty if none
	 */
	Optional<Logo> fetch(String domain);

	/** A logo image. Its type was checked from its bytes. */
	record Logo(String contentType, byte[] data) {
	}

}
