package com.nordencapital.spine.passkey;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthController.MeResponse;
import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.auth.SessionLogin;
import com.nordencapital.spine.common.ApiError;
import com.nordencapital.spine.common.Names;
import com.nordencapital.spine.user.User;

@Tag(name = "Passkeys", description = "Log in with Face ID / Touch ID / fingerprint (WebAuthn). Options are "
		+ "WebAuthn JSON (binary values in base64url): give them to PublicKeyCredential.parseCreationOptionsFromJSON / "
		+ "parseRequestOptionsFromJSON (or @simplewebauthn/browser), and send back credential.toJSON(). "
		+ "Every POST needs the X-XSRF-TOKEN header.")
@RestController
public class PasskeyController {

	private static final int MAX_NAME_LENGTH = 60;

	private final PasskeyService service;

	private final SessionLogin sessionLogin;

	public PasskeyController(PasskeyService service, SessionLogin sessionLogin) {
		this.service = service;
		this.sessionLogin = sessionLogin;
	}

	@Operation(summary = "Start adding a passkey",
			description = "Returns PublicKeyCredentialCreationOptionsJSON. Valid 5 minutes, once.")
	@PostMapping(path = "/api/me/passkeys/register/options", produces = MediaType.APPLICATION_JSON_VALUE)
	public String registrationOptions(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		return this.service.registrationOptions(actor, request.getSession());
	}

	@Operation(summary = "Finish adding a passkey",
			description = "credential = RegistrationResponseJSON (credential.toJSON()). Name: max 60, default \"Passkey\".")
	@ApiResponse(responseCode = "201", description = "Added")
	@ApiResponse(responseCode = "400", description = "passkey_invalid or validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/me/passkeys/register")
	public ResponseEntity<PasskeyResponse> register(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
		PasskeyResponse created = this.service.register(actor, request.getSession(), body.name(),
				body.credential().toString());
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@Operation(summary = "My passkeys")
	@GetMapping("/api/me/passkeys")
	public List<PasskeyResponse> list(@AuthenticationPrincipal AuthUser actor) {
		return this.service.list(actor);
	}

	@Operation(summary = "Rename one of my passkeys")
	@ApiResponse(responseCode = "200", description = "Saved")
	@ApiResponse(responseCode = "404", description = "passkey_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PatchMapping("/api/me/passkeys/{id}")
	public PasskeyResponse rename(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody RenameRequest body) {
		return this.service.rename(actor, id, body.name());
	}

	@Operation(summary = "Delete one of my passkeys")
	@ApiResponse(responseCode = "204", description = "Deleted")
	@ApiResponse(responseCode = "404", description = "passkey_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@DeleteMapping("/api/me/passkeys/{id}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		this.service.delete(actor, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Start a passkey login",
			description = "Public. Returns PublicKeyCredentialRequestOptionsJSON (no email needed). Valid 5 minutes, once.")
	@PostMapping(path = "/api/auth/passkey/options", produces = MediaType.APPLICATION_JSON_VALUE)
	public String loginOptions(HttpServletRequest request) {
		return this.service.loginOptions(request.getSession());
	}

	@Operation(summary = "Log in with a passkey",
			description = "credential = AuthenticationResponseJSON (credential.toJSON()). Starts the same session as "
					+ "password login (SESSION cookie + new XSRF-TOKEN cookie).")
	@ApiResponse(responseCode = "200", description = "Logged in")
	@ApiResponse(responseCode = "401", description = "passkey_invalid",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "429", description = "too_many_attempts",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/auth/passkey/login")
	public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		User user = this.service.login(request.getSession(), body.credential().toString(), request.getRemoteAddr());
		this.sessionLogin.start(user, request, response);
		return MeResponse.from(user);
	}

	/** The name is trimmed before validation; empty = "Passkey". */
	public record RegisterRequest(
			@Schema(nullable = true) @Size(max = MAX_NAME_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@Schema(description = "RegistrationResponseJSON") @NotNull JsonNode credential) {

		public RegisterRequest {
			name = (name == null || name.isBlank()) ? null : name.strip();
		}

	}

	/** The name is trimmed before validation. */
	public record RenameRequest(@NotBlank @Size(max = MAX_NAME_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
			message = "must not contain control characters") String name) {

		public RenameRequest {
			name = Names.clean(name);
		}

	}

	public record LoginRequest(@Schema(description = "AuthenticationResponseJSON") @NotNull JsonNode credential) {
	}

}
