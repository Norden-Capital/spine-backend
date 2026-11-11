package com.nordencapital.spine.common;

/**
 * JSON body of every error response.
 */
public record ApiError(String error, String message) {
}
