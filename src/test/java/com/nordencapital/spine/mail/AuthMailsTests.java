package com.nordencapital.spine.mail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nordencapital.spine.config.AppProperties;

import static org.assertj.core.api.Assertions.assertThat;

class AuthMailsTests {

	private final List<MailMessage> sent = new ArrayList<>();

	private final AuthMails mails = new AuthMails(this.sent::add,
			new AppProperties("https://spine.example.com/", new AppProperties.Mail("Spine <no-reply@example.com>", "", false),
					new AppProperties.Bootstrap("admin@example.com"), new AppProperties.Auth(Duration.ofHours(72),
							Duration.ofHours(1), 5, Duration.ofMinutes(15), 3, Duration.ofMinutes(15))));

	@Test
	void inviteHasTitleButtonAndLinkInHtmlAndText() {
		this.mails.sendInvite("new@example.com", "TOKEN123");
		assertThat(this.sent).hasSize(1);
		MailMessage message = this.sent.get(0);
		assertThat(message.to()).isEqualTo("new@example.com");
		assertThat(message.subject()).isEqualTo("You're invited to Spine");
		assertThat(message.html()).contains("You&#39;re invited to Spine")
			.contains("Click the button below to set your password. This link expires in 3\u00a0days.")
			.contains("<img src=\"https://spine.example.com/email/spine-logo.png\" width=\"48\" height=\"48\" alt=\"Spine\"")
			.doesNotContain(">Spine</td>")
			.contains("href=\"https://spine.example.com/set-password?token=TOKEN123\"")
			.contains(">Set password</a>")
			.contains("Button not working? Use this link:")
			.contains(">spine.example.com/set-password</a>")
			.contains("you can safely ignore it.")
			.contains("Norden Capital")
			.doesNotContain("<svg");
		assertThat(message.text()).contains("You're invited to Spine")
			.contains("This link expires in 3 days.")
			.contains("Set password: https://spine.example.com/set-password?token=TOKEN123")
			.contains("If you didn't expect this email, you can safely ignore it.")
			.doesNotContain("<");
	}

	@Test
	void resetMailSaysOneHour() {
		this.mails.sendPasswordReset("user@example.com", "TOKEN456");
		MailMessage message = this.sent.get(0);
		assertThat(message.subject()).isEqualTo("Reset your Spine password");
		assertThat(message.html()).contains("set-password?token=TOKEN456")
			.contains("Click the button below to choose a new password. This link expires in 1\u00a0hour.")
			.contains(">Reset password</a>");
		assertThat(message.text()).contains("Reset password: https://spine.example.com/set-password?token=TOKEN456")
			.contains("1 hour.");
	}

	@Test
	void layoutEscapesHtml() {
		String html = EmailLayout.html(new EmailLayout.ActionEmail("p", "<b>x</b>", "a", "1 hour", "B",
				"https://e.com/?a=1&b=\"2\""), "https://e.com/logo.png");
		assertThat(html).doesNotContain("<b>x</b>").contains("&lt;b&gt;x&lt;/b&gt;")
			.contains("https://e.com/?a=1&amp;b=&quot;2&quot;");
	}

	@Test
	void shortLinkShowsHostAndPathWithoutToken() {
		assertThat(EmailLayout.shortLink("https://spine-frontend-ten.vercel.app/set-password?token=abc"))
			.isEqualTo("spine-frontend-ten.vercel.app/set-password");
		assertThat(EmailLayout.shortLink("http://localhost:3000/set-password#x")).isEqualTo("localhost:3000/set-password");
	}

	@Test
	void timeLeftUsesDaysForWholeDays() {
		assertThat(AuthMails.timeLeft(Duration.ofHours(72))).isEqualTo("3 days");
		assertThat(AuthMails.timeLeft(Duration.ofHours(24))).isEqualTo("1 day");
		assertThat(AuthMails.timeLeft(Duration.ofHours(1))).isEqualTo("1 hour");
		assertThat(AuthMails.timeLeft(Duration.ofHours(12))).isEqualTo("12 hours");
	}

	@Test
	void failedSendDoesNotThrow() {
		AuthMails failing = new AuthMails((message) -> {
			throw new IllegalStateException("down");
		}, new AppProperties("https://spine.example.com", new AppProperties.Mail("Spine <a@b.c>", "", false),
				new AppProperties.Bootstrap(""), new AppProperties.Auth(Duration.ofHours(72), Duration.ofHours(1), 5,
						Duration.ofMinutes(15), 3, Duration.ofMinutes(15))));
		failing.sendInvite("x@example.com", "T");
	}

}
