package com.nordencapital.spine.auth;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.common.ApiError;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

@Tag(name = "Auth", description = "Login, logout, current user, forgot and set password. "
		+ "Every POST needs the X-XSRF-TOKEN header (value of the XSRF-TOKEN cookie, from GET /api/auth/csrf).")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	private final SessionLogin sessionLogin;

	private final CsrfTokenRepository csrfTokenRepository;

	private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder
		.getContextHolderStrategy();

	public AuthController(AuthService authService, SessionLogin sessionLogin, CsrfTokenRepository csrfTokenRepository) {
		this.authService = authService;
		this.sessionLogin = sessionLogin;
		this.csrfTokenRepository = csrfTokenRepository;
	}

	@Operation(summary = "Get a CSRF token", description = "Sets the XSRF-TOKEN cookie. Call it once before the first POST.")
	@ApiResponse(responseCode = "204", description = "Cookie set")
	@GetMapping("/csrf")
	public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
		// Reading the token makes Spring Security write the XSRF-TOKEN cookie.
		csrfToken.getToken();
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Log in", description = "Starts a session (SESSION cookie) and sends a new XSRF-TOKEN cookie.")
	@ApiResponse(responseCode = "200", description = "Logged in")
	@ApiResponse(responseCode = "401", description = "invalid_credentials",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "429", description = "too_many_attempts",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/login")
	public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		User loggedIn = this.authService.login(body.email(), body.password(), request.getRemoteAddr());
		this.sessionLogin.start(loggedIn, request, response);
		return MeResponse.from(loggedIn);
	}

	@Operation(summary = "Log out", description = "Deletes the session. Works even if not logged in.")
	@ApiResponse(responseCode = "204", description = "Logged out")
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		this.securityContextHolderStrategy.clearContext();
		this.csrfTokenRepository.saveToken(null, request, response);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Current user")
	@ApiResponse(responseCode = "200", description = "Logged in")
	@ApiResponse(responseCode = "401", description = "unauthorized",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal AuthUser principal, HttpServletRequest request) {
		return this.authService.current(principal).map(MeResponse::from).orElseThrow(() -> {
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Please log in");
		});
	}

	@Operation(summary = "Ask for a password reset link",
			description = "Always 202, whether the email exists or not. Only ACTIVE users get an email.")
	@ApiResponse(responseCode = "202", description = "Accepted")
	@PostMapping("/forgot-password")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest body, HttpServletRequest request) {
		this.authService.forgotPassword(body.email(), request.getRemoteAddr());
	}

	@Operation(summary = "Check an invite or reset token", description = "Used by the set-password page.")
	@ApiResponse(responseCode = "200", description = "Token is valid")
	@ApiResponse(responseCode = "410", description = "token_invalid",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/token/{token}")
	public AuthService.TokenInfo checkToken(@PathVariable String token) {
		return this.authService.checkToken(token);
	}

	@Operation(summary = "Set a password with an invite or reset token",
			description = "Marks the user ACTIVE and uses up the token. Does not log in.")
	@ApiResponse(responseCode = "204", description = "Password set")
	@ApiResponse(responseCode = "400", description = "weak_password (12 to 128 characters)",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "410", description = "token_invalid",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/set-password")
	public ResponseEntity<Void> setPassword(@Valid @RequestBody SetPasswordRequest body) {
		this.authService.setPassword(body.token(), body.password());
		return ResponseEntity.noContent().build();
	}

	public record LoginRequest(@NotBlank @Size(max = 320) String email, @NotBlank String password) {
	}

	public record ForgotPasswordRequest(@NotBlank @Size(max = 320) String email) {
	}

	public record SetPasswordRequest(@NotBlank String token, @NotNull String password) {
	}

	/**
	 * @param firstName null if not set
	 * @param lastName null if not set
	 */
	public record MeResponse(java.util.UUID id, String email, Role role, String firstName, String lastName) {

		public static MeResponse from(User user) {
			return new MeResponse(user.getId(), user.getEmail(), user.getRole(), user.getFirstName(),
					user.getLastName());
		}

	}

}
