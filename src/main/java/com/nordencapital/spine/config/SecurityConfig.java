package com.nordencapital.spine.config;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Deny by default. Public: ping, health, and the login / password endpoints.
 * {@code /api/admin/**} needs OWNER or ADMIN (finer rules in UserAccess). Everything else
 * needs a logged-in user.
 * <p>
 * Sessions are server-side (Spring Session JDBC, SESSION cookie). CSRF is on for every
 * POST/PUT/PATCH/DELETE: the client reads the XSRF-TOKEN cookie and sends it back in the
 * X-XSRF-TOKEN header.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	private static final String ARGON2 = "argon2@SpringSecurity_v5_8";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
			SecurityContextRepository securityContextRepository) throws Exception {
		http.authorizeHttpRequests((auth) -> auth
			.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
			.requestMatchers("/error").permitAll()
			.requestMatchers(HttpMethod.GET, "/api/ping", "/actuator/health", "/actuator/health/**").permitAll()
			.requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/auth/token/*").permitAll()
			.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout", "/api/auth/forgot-password",
					"/api/auth/set-password", "/api/auth/passkey/options", "/api/auth/passkey/login")
			.permitAll()
			// Swagger: only switched on by the local profile; 404 everywhere else.
			.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
			.permitAll()
			.requestMatchers("/api/admin/**").hasAnyRole("OWNER", "ADMIN")
			.anyRequest().authenticated());

		http.csrf((csrf) -> csrf.spa().csrfTokenRepository(csrfTokenRepository));
		http.cors(Customizer.withDefaults());
		http.securityContext((context) -> context.securityContextRepository(securityContextRepository));
		http.requestCache((cache) -> cache.requestCache(new NullRequestCache()));
		// Login and logout are our own JSON endpoints (AuthController).
		http.formLogin((form) -> form.disable());
		http.httpBasic((basic) -> basic.disable());
		http.logout((logout) -> logout.disable());
		http.exceptionHandling((exceptions) -> exceptions
			.authenticationEntryPoint((request, response, ex) -> writeError(response,
					HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Please log in"))
			.accessDeniedHandler((request, response, ex) -> {
				if (ex instanceof CsrfException) {
					writeError(response, HttpServletResponse.SC_FORBIDDEN, "csrf_invalid",
							"Missing or invalid CSRF token");
				}
				else {
					writeError(response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "You cannot do this");
				}
			}));
		return http.build();
	}

	/**
	 * XSRF-TOKEN cookie, readable by JavaScript, Secure in prod (same flag as the session
	 * cookie).
	 */
	@Bean
	CsrfTokenRepository csrfTokenRepository(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookiePath("/");
		repository.setCookieCustomizer((cookie) -> cookie.secure(secure).sameSite("Lax"));
		return repository;
	}

	/**
	 * SESSION cookie: HttpOnly, SameSite=Lax, path /, Secure in prod. Set here on purpose:
	 * Spring Boot does not always copy the server.servlet.session.cookie settings to Spring
	 * Session (for example in MockMvc tests), and these flags must never be missing.
	 */
	@Bean
	CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName("SESSION");
		serializer.setCookiePath("/");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setSameSite("Lax");
		serializer.setUseSecureCookie(secure);
		return serializer;
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	/**
	 * Argon2id for new passwords. Argon2 has no 72-byte limit like BCrypt, so all 128
	 * characters count. BCrypt is accepted for old hashes, should we ever import some.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new DelegatingPasswordEncoder(ARGON2, Map.of(ARGON2,
				Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), "bcrypt", new BCryptPasswordEncoder()));
	}

	private static void writeError(HttpServletResponse response, int status, String code, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		// code and message are fixed strings without quotes, so no JSON escaping is needed.
		response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}

}
