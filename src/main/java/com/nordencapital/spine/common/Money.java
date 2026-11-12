package com.nordencapital.spine.common;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

/**
 * Money text from clients: like "123.45", max 2 decimals, more than 0, at most
 * 9999999999.99.
 */
public final class Money {

	public static final String PATTERN = "(0|[1-9][0-9]{0,9})(\\.[0-9]{1,2})?";

	public static final BigDecimal MAX = new BigDecimal("9999999999.99");

	private static final Pattern REGEX = Pattern.compile(PATTERN);

	private Money() {
	}

	/**
	 * @throws ApiException 400 {@code validation_error}
	 */
	public static BigDecimal parse(String field, String text) {
		if (text == null || !REGEX.matcher(text).matches()) {
			throw invalid(field + ": must be an amount like 123.45 (max 2 decimals)");
		}
		BigDecimal amount = new BigDecimal(text).setScale(2);
		if (amount.signum() <= 0 || amount.compareTo(MAX) > 0) {
			throw invalid(field + ": must be more than 0 and at most 9999999999.99");
		}
		return amount;
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

}
