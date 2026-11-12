package com.nordencapital.spine.me;

import java.util.Optional;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A user's time zone preference.
 */
@Component
public class UserZones {

	private final UserPreferencesRepository preferences;

	public UserZones(UserPreferencesRepository preferences) {
		this.preferences = preferences;
	}

	/**
	 * @return the saved time zone, or empty if it is "automatic" (the device's zone,
	 * which the server does not know)
	 */
	@Transactional(readOnly = true)
	public Optional<ZoneId> saved(UUID userId) {
		return this.preferences.findById(userId)
			.map(UserPreferences::toPreferences)
			.map(Preferences::timeZone)
			.map(ZoneId::of);
	}

}
