package com.nordencapital.spine.mail;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.nordencapital.spine.config.AppProperties;

@Configuration(proxyBeanMethods = false)
class MailConfig {

	@Bean
	MailSender mailSender(AppProperties properties) {
		AppProperties.Mail mail = properties.mail();
		if (mail.hasResendApiKey()) {
			return new ResendMailSender(mail.resendApiKey(), mail.from());
		}
		if (mail.requireResend()) {
			throw new IllegalStateException("RESEND_API_KEY must be set (app.mail.require-resend is true)");
		}
		return new LoggingMailSender();
	}

}
