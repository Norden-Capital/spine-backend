package com.nordencapital.spine.mail;

/**
 * Sends emails. {@link ResendMailSender} when {@code RESEND_API_KEY} is set, otherwise
 * {@link LoggingMailSender} (local dev: the email is printed to the console).
 */
public interface MailSender {

	void send(MailMessage message);

}
