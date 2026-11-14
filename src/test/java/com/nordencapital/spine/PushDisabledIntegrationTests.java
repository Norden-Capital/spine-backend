package com.nordencapital.spine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.alert.AlertService;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No VAPID keys: push is off. The push endpoints answer 503 push_disabled; alerts still
 * work, only in the app.
 */
@TestPropertySource(properties = { "app.push.vapid-public-key=", "app.push.vapid-private-key=",
		"app.push.vapid-subject=" })
class PushDisabledIntegrationTests extends IntegrationTestBase {

	@Autowired
	private AlertService alerts;

	@Test
	void pushEndpointsSay503AndAlertsStillWork() {
		User ada = activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");

		assertDisabled(a.get("/api/push/key"));
		assertDisabled(a.post("/api/push/subscribe", PushIntegrationTests.subscription("https://web.push.apple.com/x")));
		assertDisabled(a.get("/api/push/status?endpoint=x"));
		assertDisabled(a.delete("/api/push/subscribe", "{\"endpoint\":\"x\"}"));
		assertDisabled(a.post("/api/push/test", "{}"));

		assertThat(this.alerts.add(ada.getId(), AlertService.BUDGET_80, "Budget almost used", "b", null, "k")).isTrue();
		assertThat(a.get("/api/alerts")).bodyJson().extractingPath("$.unreadCount").isEqualTo(1);
		assertThat(this.pushes.all()).isEmpty();
	}

	private static void assertDisabled(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("push_disabled");
	}

}
