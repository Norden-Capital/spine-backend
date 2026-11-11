package com.nordencapital.spine.common;

import org.springframework.http.HttpStatus;

/**
 * An error the API returns to the client as {@code {"error":"code","message":"..."}}.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return this.status;
	}

	public String getCode() {
		return this.code;
	}

}
