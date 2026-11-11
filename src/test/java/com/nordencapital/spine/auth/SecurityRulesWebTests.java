package com.nordencapital.spine.auth;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.config.SecurityConfig;
import com.nordencapital.spine.ping.PingController;
import com.nordencapital.spine.user.AdminUserController;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.UserAdminService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Security rules and CSRF on the web layer, with the services mocked. Runs without Docker.
 */
@WebMvcTest(controllers = { AuthController.class, AdminUserController.class, PingController.class })
@Import(SecurityConfig.class)
class SecurityRulesWebTests {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private UserAdminService userAdminService;

	@Test
	void pingIsPublic() {
		assertThat(this.mvc.get().uri("/api/ping")).hasStatus(HttpStatus.OK);
	}

	@Test
	void unknownApiPathNeedsLogin() {
		assertThat(this.mvc.get().uri("/api/something")).hasStatus(HttpStatus.UNAUTHORIZED)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("unauthorized");
	}

	@Test
	void meNeedsLogin() {
		assertThat(this.mvc.get().uri("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void adminUsersNeedLogin() {
		assertThat(this.mvc.get().uri("/api/admin/users")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void employeeCannotUseAdminApi() {
		assertThat(this.mvc.get().uri("/api/admin/users").with(as(Role.EMPLOYEE))).hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("forbidden");
	}

	@Test
	void adminCanUseAdminApi() {
		given(this.userAdminService.list()).willReturn(List.of());
		assertThat(this.mvc.get().uri("/api/admin/users").with(as(Role.ADMIN))).hasStatus(HttpStatus.OK);
	}

	@Test
	void employeeCannotInviteEvenWithCsrf() {
		String csrf = csrfToken();
		assertThat(this.mvc.post()
			.uri("/api/admin/users")
			.with(as(Role.EMPLOYEE))
			.cookie(new Cookie("XSRF-TOKEN", csrf))
			.header("X-XSRF-TOKEN", csrf)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"x@example.com\",\"role\":\"EMPLOYEE\"}")).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void csrfEndpointSetsReadableCookie() {
		MvcTestResult result = this.mvc.get().uri("/api/auth/csrf").exchange();
		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		assertThat(cookie.getValue()).isNotBlank();
		assertThat(cookie.isHttpOnly()).isFalse();
		assertThat(cookie.getPath()).isEqualTo("/");
	}

	@Test
	void loginWithoutCsrfIsRejected() {
		assertThat(this.mvc.post()
			.uri("/api/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"a@example.com\",\"password\":\"whatever-password\"}"))
			.hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("csrf_invalid");
	}

	@Test
	void loginWithWrongCsrfHeaderIsRejected() {
		String csrf = csrfToken();
		assertThat(this.mvc.post()
			.uri("/api/auth/login")
			.cookie(new Cookie("XSRF-TOKEN", csrf))
			.header("X-XSRF-TOKEN", "not-the-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"a@example.com\",\"password\":\"whatever-password\"}"))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void loginWithCsrfReturnsUserAndNewCsrfCookie() {
		AuthUser user = new AuthUser(UUID.randomUUID(), "a@example.com", Role.EMPLOYEE);
		given(this.authService.login(eq("a@example.com"), eq("correct-password"), anyString())).willReturn(user);
		String csrf = csrfToken();
		MvcTestResult result = this.mvc.post()
			.uri("/api/auth/login")
			.cookie(new Cookie("XSRF-TOKEN", csrf))
			.header("X-XSRF-TOKEN", csrf)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"a@example.com\",\"password\":\"correct-password\"}")
			.exchange();
		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("a@example.com");
		assertThat(result).bodyJson().extractingPath("$.role").isEqualTo("EMPLOYEE");
		assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(user.id().toString());
		Cookie newCsrf = result.getResponse().getCookie("XSRF-TOKEN");
		assertThat(newCsrf).isNotNull();
		assertThat(newCsrf.getValue()).isNotEqualTo(csrf);
	}

	@Test
	void badLoginGivesJsonError() {
		given(this.authService.login(anyString(), anyString(), anyString())).willThrow(
				new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid email or password"));
		assertThat(postWithCsrf("/api/auth/login", "{\"email\":\"a@example.com\",\"password\":\"wrong-password\"}"))
			.hasStatus(HttpStatus.UNAUTHORIZED)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("invalid_credentials");
	}

	@Test
	void loginWithMissingFieldsIsBadRequest() {
		assertThat(postWithCsrf("/api/auth/login", "{\"email\":\"\"}")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("validation_error");
	}

	@Test
	void forgotPasswordIsPublicAndAccepted() {
		assertThat(postWithCsrf("/api/auth/forgot-password", "{\"email\":\"nobody@example.com\"}"))
			.hasStatus(HttpStatus.ACCEPTED);
		verify(this.authService).forgotPassword(eq("nobody@example.com"), anyString());
	}

	@Test
	void tokenCheckIsPublic() {
		given(this.authService.checkToken("abc"))
			.willReturn(new AuthService.TokenInfo("a@example.com", TokenPurpose.INVITE));
		assertThat(this.mvc.get().uri("/api/auth/token/abc")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.purpose")
			.isEqualTo("INVITE");
	}

	@Test
	void logoutWorksWithoutSession() {
		assertThat(postWithCsrf("/api/auth/logout", "")).hasStatus(HttpStatus.NO_CONTENT);
	}

	@Test
	void adminPatchPassesActorId() {
		AuthUser admin = new AuthUser(UUID.randomUUID(), "admin@example.com", Role.ADMIN);
		UUID target = UUID.randomUUID();
		String csrf = csrfToken();
		assertThat(this.mvc.patch()
			.uri("/api/admin/users/" + target)
			.with(as(admin))
			.cookie(new Cookie("XSRF-TOKEN", csrf))
			.header("X-XSRF-TOKEN", csrf)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"DISABLED\"}")).hasStatus(HttpStatus.OK);
		verify(this.userAdminService).update(eq(target), any(), eq(com.nordencapital.spine.user.UserStatus.DISABLED),
				eq(admin.id()));
	}

	private MvcTestResult postWithCsrf(String uri, String json) {
		String csrf = csrfToken();
		return this.mvc.post()
			.uri(uri)
			.cookie(new Cookie("XSRF-TOKEN", csrf))
			.header("X-XSRF-TOKEN", csrf)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)
			.exchange();
	}

	private String csrfToken() {
		return this.mvc.get().uri("/api/auth/csrf").exchange().getResponse().getCookie("XSRF-TOKEN").getValue();
	}

	private static RequestPostProcessor as(Role role) {
		return as(new AuthUser(UUID.randomUUID(), role.name().toLowerCase() + "@example.com", role));
	}

	private static RequestPostProcessor as(AuthUser user) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
	}

}
