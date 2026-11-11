package com.nordencapital.spine.user;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;
import com.nordencapital.spine.common.Emails;

@Tag(name = "Admin: users", description = "ADMIN only.")
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

	private final UserAdminService service;

	public AdminUserController(UserAdminService service) {
		this.service = service;
	}

	@Operation(summary = "List all users")
	@GetMapping
	public List<UserResponse> list() {
		return this.service.list();
	}

	@Operation(summary = "Invite a user", description = "Creates the user (INVITED) and emails a set-password link.")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "409", description = "email_taken",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping
	public ResponseEntity<UserResponse> invite(@Valid @RequestBody InviteRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.invite(body.email(), body.role()));
	}

	@Operation(summary = "Send a new invite link", description = "Only for INVITED users. The old link stops working.")
	@ApiResponse(responseCode = "204", description = "Sent")
	@ApiResponse(responseCode = "404", description = "user_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "not_invited",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/{id}/resend-invite")
	public ResponseEntity<Void> resendInvite(@PathVariable UUID id) {
		this.service.resendInvite(id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Change role and/or status",
			description = "status is ACTIVE or DISABLED. Disabling or changing the role logs the user out everywhere.")
	@ApiResponse(responseCode = "200", description = "Updated")
	@ApiResponse(responseCode = "404", description = "user_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "last_admin or cannot_disable_self",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PatchMapping("/{id}")
	public UserResponse update(@PathVariable UUID id, @RequestBody UpdateRequest body,
			@AuthenticationPrincipal AuthUser actor) {
		return this.service.update(id, body.role(), body.status(), actor.id());
	}

	public record InviteRequest(@NotBlank @Email @Size(max = 320) String email, @NotNull Role role) {

		// Trim and lowercase before validation, so " New@Example.com " is accepted.
		public InviteRequest {
			email = Emails.normalize(email);
		}

	}

	public record UpdateRequest(Role role, @Schema(allowableValues = { "ACTIVE", "DISABLED" }) UserStatus status) {
	}

}
