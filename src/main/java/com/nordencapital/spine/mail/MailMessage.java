package com.nordencapital.spine.mail;

/**
 * One email to one person, with an HTML body and a plain-text body (see {@link EmailLayout}).
 */
public record MailMessage(String to, String subject, String html, String text) {
}
