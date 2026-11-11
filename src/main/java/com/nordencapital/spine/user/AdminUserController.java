package com.nordencapital.spine.user;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;
import com.nordencapital.spine.common.Emails;
import com.nordencapital.spine.me.SignInDay;

@Tag(name = "Admin: users", description = "OWNER and ADMIN only. ADMIN sees EMPLOYEEs and other ADMINs and manages "
		+ "only EMPLOYEEs. OWNER sees and manages every ADMIN and EMPLOYEE. Nobody sees themself or the OWNER here. "
		+ "A user you cannot see is 404; one you can see but not manage is 403 forbidden.")
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

	private final UserAdminService service;

	public AdminUserController(UserAdminService service) {
		this.service = service;
	}

	@Operation(summary = "List the users I can see")
	@GetMapping
	public List<UserResponse> list(@AuthenticationPrincipal AuthUser actor) {
		return this.service.list(actor);
	}

	@Operation(summary = "One user")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "user_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/{id}")
	public UserResponse get(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.get(actor, id);
	}

	@Operation(summary = "A user's logins per day",
			description = "Days with at least one login attempt, oldest first, counted in MY time zone preference "
					+ "(UTC if automatic).")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "user_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/{id}/sign-ins")
	public List<SignInDay> signIns(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Parameter(description = "First day, YYYY-MM-DD. Default: today minus 6 months.") @RequestParam(
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
		return this.service.signIns(actor, id, from);
	}

	@Operation(summary = "Invite a user",
			description = "Creates the user (INVITED) and emails a set-password link. ADMIN can invite EMPLOYEE; "
					+ "OWNER can invite ADMIN or EMPLOYEE.")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "403", description = "forbidden (role not allowed)",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "email_taken",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping
	public ResponseEntity<UserResponse> invite(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody InviteRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.invite(actor, body.email(), body.role()));
	}

	@Operation(summary = "Send a new invite link", description = "Only for INVITED users. The old link stops working.")
	@ApiResponse(responseCode = "204", description = "Sent")
	@ApiResponse(responseCode = "403", description = "forbidden",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "user_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "not_invited",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/{id}/resend-invite")
	public ResponseEntity<Void> resendInvite(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		this.service.resendInvite(actor, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Disable a user", description = "Logs them out everywhere; their links stop working.")
	@ApiResponse(responseCode = "200", description = "Disabled")
	@ApiResponse(responseCode = "403", description = "forbidden",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "user_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/{id}/disable")
	public UserResponse disable(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.disable(actor, id);
	}

	@Operation(summary = "Enable a disabled user",
			description = "ACTIVE again, or INVITED if they never set a password.")
	@ApiResponse(responseCode = "200", description = "Enabled")
	@ApiResponse(responseCode = "403", description = "forbidden",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "user_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/{id}/enable")
	public UserResponse enable(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.enable(actor, id);
	}

	@Operation(summary = "Log a user out everywhere")
	@ApiResponse(responseCode = "204", description = "Logged out")
	@ApiResponse(responseCode = "403", description = "forbidden",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "user_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/{id}/logout-all")
	public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		this.service.logoutAll(actor, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Email a user a reset-password link", description = "Only ACTIVE users. The link works 1 hour.")
	@ApiResponse(responseCode = "202", description = "Sent")
	@ApiResponse(responseCode = "403", description = "forbidden",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "user_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "not_active",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/{id}/reset-password")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void resetPassword(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		this.service.sendPasswordReset(actor, id);
	}

	public record InviteRequest(@NotBlank @Email @Size(max = 320) String email,
			@Schema(allowableValues = { "ADMIN", "EMPLOYEE" }) @NotNull Role role) {

		// Trim and lowercase before validation, so " New@Example.com " is accepted.
		public InviteRequest {
			email = Emails.normalize(email);
		}

	}

}
