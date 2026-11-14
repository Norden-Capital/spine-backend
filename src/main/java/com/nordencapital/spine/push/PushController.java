package com.nordencapital.spine.push;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;

@Tag(name = "Push", description = "Phone notifications (Web Push). One subscription per device; only my own. "
		+ "503 push_disabled when the server has no VAPID keys.")
@ApiResponse(responseCode = "503", description = "push_disabled",
		content = @Content(schema = @Schema(implementation = ApiError.class)))
@RestController
public class PushController {

	private final PushService service;

	public PushController(PushService service) {
		this.service = service;
	}

	@Operation(summary = "The VAPID public key", description = "Use it as applicationServerKey in pushManager.subscribe.")
	@GetMapping("/api/push/key")
	public Key key(@AuthenticationPrincipal AuthUser actor) {
		return new Key(this.service.publicKey(actor));
	}

	@Operation(summary = "Turn on for this device",
			description = "The browser's PushSubscription JSON. Same endpoint again = updated (and moved to me).")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/push/subscribe")
	public PushService.Status subscribe(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody SubscribeRequest body,
			@RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return this.service.subscribe(actor, body.endpoint(), body.keys().p256dh(), body.keys().auth(), userAgent);
	}

	@Operation(summary = "Turn off for this device", description = "Always 204 (unknown or not mine: nothing changes).")
	@ApiResponse(responseCode = "204", description = "Done")
	@DeleteMapping("/api/push/subscribe")
	public ResponseEntity<Void> unsubscribe(@AuthenticationPrincipal AuthUser actor,
			@RequestBody EndpointRequest body) {
		this.service.unsubscribe(actor, body.endpoint());
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Is this device on for me?")
	@GetMapping("/api/push/status")
	public PushService.Status status(@AuthenticationPrincipal AuthUser actor,
			@RequestParam(required = false) String endpoint) {
		return this.service.status(actor, endpoint);
	}

	@Operation(summary = "Send \"Spine test\" to all my devices", description = "429 too_many_attempts after 5 in 10 minutes.")
	@PostMapping("/api/push/test")
	public PushService.TestResult test(@AuthenticationPrincipal AuthUser actor) {
		return this.service.test(actor);
	}

	public record Key(String publicKey) {
	}

	public record SubscribeRequest(@NotBlank @Size(max = 2048) String endpoint, @NotNull @Valid Keys keys) {
	}

	public record Keys(@NotBlank @Size(max = 128) String p256dh, @NotBlank @Size(max = 64) String auth) {
	}

	public record EndpointRequest(String endpoint) {
	}

}
