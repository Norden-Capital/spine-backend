package com.nordencapital.spine.me;

import java.time.LocalDate;

/**
 * Login attempts on one day (in the user's time zone).
 * @param ok successful logins
 * @param failed failed logins
 */
public record SignInDay(LocalDate date, int ok, int failed) {
}
