package com.nordencapital.spine.me;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;

@Tag(name = "Me", description = "The logged-in user's own profile, login history, password and preferences. "
		+ "Every write needs the X-XSRF-TOKEN header.")
@RestController
@RequestMapping("/api/me")
public class MeController {

	private static final String NO_CONTROL_CHARS = "[^\\p{Cntrl}]*";

	private final MeService meService;

	public MeController(MeService meService) {
		this.meService = meService;
	}

	@Operation(summary = "My profile")
	@ApiResponse(responseCode = "200", description = "OK")
	@GetMapping
	public ProfileResponse profile(@AuthenticationPrincipal AuthUser principal) {
		return ProfileResponse.from(this.meService.profile(principal));
	}

	@Operation(summary = "Change my first and last name",
			description = "Sets both names. Spaces around are removed; empty or null = not set. Max 60 characters each.")
	@ApiResponse(responseCode = "200", description = "Saved")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PatchMapping
	public ProfileResponse updateNames(@AuthenticationPrincipal AuthUser principal,
			@Valid @RequestBody NamesRequest body) {
		return ProfileResponse.from(this.meService.updateNames(principal, body.firstName(), body.lastName()));
	}

	@Operation(summary = "My logins per day",
			description = "Days with at least one login attempt, oldest first. Days are counted in my time zone "
					+ "preference (UTC if automatic).")
	@ApiResponse(responseCode = "200", description = "OK")
	@GetMapping("/sign-ins")
	public List<SignInDay> signIns(@AuthenticationPrincipal AuthUser principal,
			@Parameter(description = "First day, YYYY-MM-DD. Default: today minus 6 months.") @RequestParam(
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
		return this.meService.signIns(principal, from);
	}

	@Operation(summary = "Change my password",
			description = "Needs the current password. I stay logged in here; my other sessions are logged out.")
	@ApiResponse(responseCode = "204", description = "Password changed")
	@ApiResponse(responseCode = "400",
			description = "invalid_current_password, or weak_password (12 to 128 characters)",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "429", description = "too_many_attempts",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/password")
	public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthUser principal,
			@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		this.meService.changePassword(principal, body.currentPassword(), body.newPassword(),
				(session != null) ? session.getId() : null);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "My preferences", description = "Defaults if never saved.")
	@ApiResponse(responseCode = "200", description = "OK")
	@GetMapping("/preferences")
	public Preferences preferences(@AuthenticationPrincipal AuthUser principal) {
		return this.meService.preferences(principal);
	}

	@Operation(summary = "Save my preferences", description = "Send all fields.")
	@ApiResponse(responseCode = "200", description = "Saved")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PutMapping("/preferences")
	public Preferences savePreferences(@AuthenticationPrincipal AuthUser principal,
			@Valid @RequestBody Preferences body) {
		return this.meService.savePreferences(principal, body);
	}

	/**
	 * Names are trimmed before validation; blank becomes null.
	 */
	public record NamesRequest(
			@Schema(nullable = true) @Size(max = 60) @Pattern(regexp = NO_CONTROL_CHARS,
					message = "must not contain control characters") String firstName,
			@Schema(nullable = true) @Size(max = 60) @Pattern(regexp = NO_CONTROL_CHARS,
					message = "must not contain control characters") String lastName) {

		public NamesRequest {
			firstName = clean(firstName);
			lastName = clean(lastName);
		}

		private static String clean(String name) {
			return (name == null || name.isBlank()) ? null : name.strip();
		}

	}

	public record ChangePasswordRequest(@NotNull String currentPassword, @NotNull String newPassword) {
	}

}
