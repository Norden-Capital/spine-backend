package com.nordencapital.spine.push;

import java.security.interfaces.ECPublicKey;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * VAPID settings: none = push off; all three, matching = on; anything else stops the app
 * at startup with a clear message.
 */
class VapidKeysTests {

	private static final String PUBLIC = "BNsOjiQ6w_cTdiH7jnH2Bivg1BDg0NHOqiXzn3x62feA5Zp-b-NZM4lsBxqQ40RvY9ga-0hW9juDL18by0a-48k";

	private static final String PRIVATE = "TIauvUc90BxBKI6-g_oOaXXiiMJRbVQ-iAggdytmj1k";

	@Test
	void noneMeansOff() {
		assertThat(VapidKeys.from(new PushProperties(null, null, null))).isNull();
		assertThat(VapidKeys.from(new PushProperties("", " ", ""))).isNull();
	}

	@Test
	void matchingKeysMeanOn() {
		VapidKeys keys = VapidKeys.from(new PushProperties(PUBLIC, PRIVATE, "mailto:ops@example.com"));
		assertThat(keys.publicKeyText()).isEqualTo(PUBLIC);
		assertThat(keys.toString()).doesNotContain(PRIVATE);
	}

	@Test
	void badSettingsStopTheApp() {
		assertThatIllegalStateException().isThrownBy(() -> VapidKeys.from(new PushProperties(PUBLIC, "", "")))
			.withMessageContaining("SPINE_VAPID_PRIVATE_KEY is empty");
		assertThatIllegalStateException()
			.isThrownBy(() -> VapidKeys.from(new PushProperties(PUBLIC, PRIVATE, "ops@example.com")))
			.withMessageContaining("mailto:");
		assertThatIllegalStateException()
			.isThrownBy(() -> VapidKeys.from(new PushProperties(PUBLIC, "abc", "mailto:ops@example.com")))
			.withMessageContaining("not valid");
		String otherPublic = WebPushCrypto
			.base64Url(WebPushCrypto.encode((ECPublicKey) WebPushCrypto.newKeyPair().getPublic()));
		assertThatIllegalStateException()
			.isThrownBy(() -> VapidKeys.from(new PushProperties(otherPublic, PRIVATE, "mailto:ops@example.com")))
			.withMessageContaining("does not belong");
	}

	@Test
	void onlyKnownPushServices() {
		assertThat(PushService.checkEndpoint(" https://fcm.googleapis.com/fcm/send/x ")).isEqualTo(
				"https://fcm.googleapis.com/fcm/send/x");
		assertThat(PushService.checkEndpoint("https://web.push.apple.com/x")).isNotNull();
		assertThat(PushService.checkEndpoint("https://wns2-db5p.notify.windows.com/w/?token=x")).isNotNull();
		for (String bad : new String[] { "https://push.apple.com.evil.com/x", "https://evilfcm.googleapis.com/x",
				"https://localhost/x", "https://169.254.169.254/x", "ftp://fcm.googleapis.com/x", "not a url" }) {
			assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> PushService.checkEndpoint(bad)))
				.as(bad)
				.isNotNull();
		}
	}

}
