package com.nordencapital.spine.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.nordencapital.spine.config.AppProperties;

/**
 * Builds and sends the invite and password reset emails.
 * <p>
 * Emails are sent after the database transaction commits, so a rolled back change never
 * sends an email. A failed send is logged and does not fail the request (an admin can
 * resend an invite; a user can ask for a new reset link).
 */
@Component
public class AuthMails {

	private static final Logger logger = LoggerFactory.getLogger(AuthMails.class);

	private final MailSender sender;

	private final String frontendUrl;

	private final AppProperties.Auth settings;

	public AuthMails(MailSender sender, AppProperties properties) {
		this.sender = sender;
		this.frontendUrl = stripTrailingSlash(properties.frontendUrl());
		this.settings = properties.auth();
	}

	public void sendInvite(String to, String rawToken) {
		send(to, new EmailLayout.ActionEmail("Set your password to start using Spine.", "You're invited to Spine",
				"set your password", timeLeft(this.settings.inviteTtl()), "Set password", setPasswordLink(rawToken)));
	}

	public void sendPasswordReset(String to, String rawToken) {
		send(to, new EmailLayout.ActionEmail("Use this link to choose a new Spine password.",
				"Reset your Spine password", "choose a new password", timeLeft(this.settings.resetTtl()),
				"Reset password", setPasswordLink(rawToken)));
	}

	private void send(String to, EmailLayout.ActionEmail email) {
		String logoUrl = this.frontendUrl + EmailLayout.LOGO_PATH;
		sendAfterCommit(new MailMessage(to, email.title(), EmailLayout.html(email, logoUrl), EmailLayout.text(email)));
	}

	String setPasswordLink(String rawToken) {
		return this.frontendUrl + "/set-password?token=" + rawToken;
	}

	private void sendAfterCommit(MailMessage message) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					sendNow(message);
				}
			});
		}
		else {
			sendNow(message);
		}
	}

	private void sendNow(MailMessage message) {
		try {
			this.sender.send(message);
		}
		catch (RuntimeException ex) {
			// Do not log the message body: it holds the token.
			logger.error("Could not send email '{}' to {}: {}", message.subject(), message.to(), ex.getMessage());
		}
	}

	/** "1 hour", "12 hours", "3 days": whole days are written in days. */
	static String timeLeft(java.time.Duration ttl) {
		long hours = Math.max(1, ttl.toHours());
		if (hours % 24 == 0) {
			long days = hours / 24;
			return days + ((days == 1) ? " day" : " days");
		}
		return hours + ((hours == 1) ? " hour" : " hours");
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

}
