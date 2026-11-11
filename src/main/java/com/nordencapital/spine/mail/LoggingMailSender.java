package com.nordencapital.spine.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local dev only: prints the plain-text email (with its link) to the console instead of
 * sending it.
 */
public class LoggingMailSender implements MailSender {

	private static final Logger logger = LoggerFactory.getLogger(LoggingMailSender.class);

	@Override
	public void send(MailMessage message) {
		logger.info("""

				----- EMAIL (not sent, RESEND_API_KEY is empty) -----
				To:      {}
				Subject: {}
				{}
				------------------------------------------------------""", message.to(), message.subject(),
				message.text());
	}

}
