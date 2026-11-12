package com.nordencapital.spine.alert;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;

@Tag(name = "Alerts", description = "My in-app alerts (budgets, recurring items). Only mine.")
@RestController
public class AlertController {

	private final AlertService service;

	public AlertController(AlertService service) {
		this.service = service;
	}

	@Operation(summary = "My alerts", description = "Newest 50 and the unread count.")
	@GetMapping("/api/alerts")
	public AlertService.Alerts list(@AuthenticationPrincipal AuthUser actor) {
		return this.service.list(actor);
	}

	@Operation(summary = "Mark one alert as read")
	@ApiResponse(responseCode = "204", description = "Done")
	@ApiResponse(responseCode = "404", description = "alert_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/alerts/{id}/read")
	public ResponseEntity<Void> read(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		this.service.markRead(actor, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Mark all my alerts as read")
	@PostMapping("/api/alerts/read-all")
	public ResponseEntity<Void> readAll(@AuthenticationPrincipal AuthUser actor) {
		this.service.markAllRead(actor);
		return ResponseEntity.noContent().build();
	}

}
