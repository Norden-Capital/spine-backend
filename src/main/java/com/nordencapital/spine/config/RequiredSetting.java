package com.nordencapital.spine.config;

/**
 * Spring Boot keeps an unresolved {@code ${ENV_VAR}} as plain text when it binds
 * settings. This check turns a missing env var into a startup error instead.
 */
final class RequiredSetting {

	private RequiredSetting() {
	}

	static String check(String name, String value) {
		if (value != null && value.contains("${")) {
			throw new IllegalStateException(name + " is not set (env var missing?): " + value);
		}
		return value;
	}

}
