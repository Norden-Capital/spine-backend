package com.nordencapital.spine;

import java.security.interfaces.ECPublicKey;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.alert.AlertService;
import com.nordencapital.spine.push.WebPushCrypto;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web Push: my devices only (subscribe, status, unsubscribe), each new alert pushed after
 * the commit (company alerts to everyone, private to one person), gone devices removed,
 * the test push.
 */
class PushIntegrationTests extends IntegrationTestBase {

	private static final String PHONE = "https://web.push.apple.com/QGpH-phone";

	private static final String LAPTOP = "https://fcm.googleapis.com/fcm/send/laptop";

	private static final String TABLET = "https://updates.push.services.mozilla.com/wpush/v2/tablet";

	@Autowired
	private AlertService alerts;

	@Test
	void myDevicesOnly() {
		User ada = activeUser("ada@example.com", Role.MEMBER);
		User bob = activeUser("bob@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		Client b = loggedIn("bob@example.com");

		assertThat(client().get("/api/push/key")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(a.get("/api/push/key")).bodyJson().extractingPath("$.publicKey").isEqualTo(VAPID_PUBLIC);

		MvcTestResult on = a.post("/api/push/subscribe", subscription(PHONE));
		assertThat(on).hasStatusOk();
		assertThat(on).bodyJson().extractingPath("$.subscribed").isEqualTo(true);
		assertThat(status(a, PHONE)).isTrue();
		assertThat(status(b, PHONE)).isFalse();
		assertThat(a.get("/api/push/status")).bodyJson().extractingPath("$.enabled").isEqualTo(true);
		assertThat(this.jdbc.queryForObject("SELECT label FROM push_subscriptions", String.class)).isNull();

		// Again: updated, not doubled.
		assertThat(a.post("/api/push/subscribe", subscription(PHONE))).hasStatusOk();
		assertThat(rows()).isEqualTo(1);

		// Bob cannot turn off Ada's device.
		assertThat(b.delete("/api/push/subscribe", "{\"endpoint\":\"" + PHONE + "\"}")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(status(a, PHONE)).isTrue();

		// Bad input.
		assertError(a.post("/api/push/subscribe", subscription("http://web.push.apple.com/x")));
		assertError(a.post("/api/push/subscribe", subscription("https://evil.example.com/push")));
		assertError(a.post("/api/push/subscribe", subscription("https://user@fcm.googleapis.com/x")));
		assertError(a.post("/api/push/subscribe", "{\"endpoint\":\"" + LAPTOP + "\",\"keys\":{\"p256dh\":\"abc\","
				+ "\"auth\":\"" + auth() + "\"}}"));
		assertError(a.post("/api/push/subscribe", "{\"endpoint\":\"" + LAPTOP + "\",\"keys\":{\"p256dh\":\""
				+ p256dh() + "\",\"auth\":\"abc\"}}"));
		assertError(a.post("/api/push/subscribe", "{\"endpoint\":\"" + LAPTOP + "\"}"));

		// The same phone, now used by Bob: it moves to him.
		assertThat(b.post("/api/push/subscribe", subscription(PHONE))).hasStatusOk();
		assertThat(status(a, PHONE)).isFalse();
		assertThat(status(b, PHONE)).isTrue();
		assertThat(rows()).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT user_id FROM push_subscriptions", UUID.class)).isEqualTo(bob.getId());

		assertThat(b.delete("/api/push/subscribe", "{\"endpoint\":\"" + PHONE + "\"}")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(rows()).isZero();
		assertThat(ada.getId()).isNotNull();
	}

	@Test
	void eachNewAlertIsPushedAfterTheCommit() {
		User ada = activeUser("ada@example.com", Role.MEMBER);
		User bob = activeUser("bob@example.com", Role.OWNER);
		disabledUser("gone@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		Client b = loggedIn("bob@example.com");
		a.post("/api/push/subscribe", subscription(PHONE));
		a.post("/api/push/subscribe", subscription(LAPTOP));
		b.post("/api/push/subscribe", subscription(TABLET));

		// Private: only Ada's 2 devices.
		this.transaction.executeWithoutResult((tx) -> this.alerts.add(ada.getId(), AlertService.BUDGET_80,
				"Budget almost used", "You have used 80% of your Meals budget this month.", "/wallet/budgets", "k1"));
		List<CapturingPushSender.Sent> sent = this.pushes.all();
		assertThat(sent).extracting(CapturingPushSender.Sent::endpoint).containsExactlyInAnyOrder(PHONE, LAPTOP);
		String alertId = this.jdbc.queryForObject("SELECT id::text FROM notifications WHERE dedupe_key = 'k1'",
				String.class);
		assertThat(sent.get(0).payload()).isEqualTo("{\"title\":\"Budget almost used\",\"body\":\"You have used 80% of "
				+ "your Meals budget this month.\",\"url\":\"/wallet/budgets\",\"tag\":\"" + alertId + "\",\"unread\":1}");

		// Same alert again (dedupe): no new row, no push.
		this.pushes.clear();
		this.alerts.add(ada.getId(), AlertService.BUDGET_80, "Budget almost used", "x", "/wallet/budgets", "k1");
		assertThat(this.pushes.all()).isEmpty();

		// Rolled back: nothing is pushed.
		this.transaction.executeWithoutResult((tx) -> {
			this.alerts.add(ada.getId(), AlertService.BUDGET_100, "Budget used up", "x", null, "k2");
			tx.setRollbackOnly();
		});
		assertThat(this.pushes.all()).isEmpty();

		// Company: everyone active (not the disabled person), link null = /alerts.
		assertThat(this.alerts.addForCompany("BALANCE_EMPTY", "Twilio is empty", "Recharge it.", null, "c1")).isEqualTo(2);
		assertThat(this.pushes.all()).extracting(CapturingPushSender.Sent::endpoint)
			.containsExactlyInAnyOrder(PHONE, LAPTOP, TABLET);
		assertThat(this.pushes.all()).allSatisfy((push) -> assertThat(push.payload()).contains("\"url\":\"/alerts\""));
		assertThat(this.pushes.all()).filteredOn((push) -> push.endpoint().equals(PHONE))
			.singleElement()
			.satisfies((push) -> assertThat(push.payload()).endsWith("\"unread\":2}"));
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE last_ok_at IS NOT NULL",
				Integer.class)).isEqualTo(3);
		assertThat(bob.getId()).isNotNull();
	}

	@Test
	void goneDevicesAreRemoved() {
		User ada = activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		a.post("/api/push/subscribe", subscription(PHONE));
		a.post("/api/push/subscribe", subscription(LAPTOP));
		a.post("/api/push/subscribe", subscription(TABLET));
		this.pushes.answer(PHONE, 410);
		this.pushes.answer(LAPTOP, 500);

		for (int i = 1; i <= 4; i++) {
			this.alerts.add(ada.getId(), AlertService.BUDGET_80, "Alert " + i, "b", null, "k" + i);
		}
		// 410: gone at once. 500 four times: still there.
		assertThat(status(a, PHONE)).isFalse();
		assertThat(this.jdbc.queryForObject("SELECT failures FROM push_subscriptions WHERE endpoint = ?",
				Integer.class, LAPTOP)).isEqualTo(4);
		// The 5th failure in a row removes it.
		this.alerts.add(ada.getId(), AlertService.BUDGET_80, "Alert 5", "b", null, "k5");
		assertThat(status(a, LAPTOP)).isFalse();
		assertThat(status(a, TABLET)).isTrue();

		// Subscribing again starts from 0 failures.
		this.pushes.answer(LAPTOP, 201);
		a.post("/api/push/subscribe", subscription(LAPTOP));
		assertThat(this.jdbc.queryForObject("SELECT failures FROM push_subscriptions WHERE endpoint = ?",
				Integer.class, LAPTOP)).isZero();
	}

	@Test
	void testNotification() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/push/test", "{}")).bodyJson().extractingPath("$.devices").isEqualTo(0);

		a.post("/api/push/subscribe", subscription(PHONE));
		MvcTestResult test = a.post("/api/push/test", "{}");
		assertThat(test).bodyJson().extractingPath("$.devices").isEqualTo(1);
		assertThat(test).bodyJson().extractingPath("$.sent").isEqualTo(1);
		assertThat(this.pushes.all()).singleElement()
			.satisfies((push) -> assertThat(push.payload()).startsWith("{\"title\":\"Spine test\""));
		// The test push is not an alert.
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isZero();

		for (int i = 0; i < 3; i++) {
			a.post("/api/push/test", "{}");
		}
		MvcTestResult tooMany = a.post("/api/push/test", "{}");
		assertThat(tooMany).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(tooMany).bodyJson().extractingPath("$.error").isEqualTo("too_many_attempts");
	}

	// ---- helpers ----

	static String subscription(String endpoint) {
		return "{\"endpoint\":\"" + endpoint + "\",\"expirationTime\":null,\"keys\":{\"p256dh\":\"" + p256dh()
				+ "\",\"auth\":\"" + auth() + "\"}}";
	}

	private static String p256dh() {
		return WebPushCrypto.base64Url(WebPushCrypto.encode((ECPublicKey) WebPushCrypto.newKeyPair().getPublic()));
	}

	private static String auth() {
		byte[] secret = new byte[16];
		new java.security.SecureRandom().nextBytes(secret);
		return WebPushCrypto.base64Url(secret);
	}

	private static boolean status(Client client, String endpoint) {
		MvcTestResult result = client.get("/api/push/status?endpoint={endpoint}".replace("{endpoint}", endpoint));
		assertThat(result).hasStatusOk();
		try {
			return result.getResponse().getContentAsString().contains("\"subscribed\":true");
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private int rows() {
		return this.jdbc.queryForObject("SELECT count(*) FROM push_subscriptions", Integer.class);
	}

	private static void assertError(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
	}

}
