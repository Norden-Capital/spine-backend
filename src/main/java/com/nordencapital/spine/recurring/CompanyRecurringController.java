package com.nordencapital.spine.recurring;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;

@Tag(name = "Repeat expenses", description = "Company repeat expenses: REMINDERS ONLY, nothing is created "
		+ "automatically. Everyone reads; /api/admin/recurring is for the OWNER.")
@RestController
public class CompanyRecurringController {

	private final CompanyRecurring service;

	public CompanyRecurringController(CompanyRecurring service) {
		this.service = service;
	}

	@Operation(summary = "All repeat expenses", description = "Next date first; finished ones (nextDate null) last.")
	@GetMapping("/api/recurring")
	public List<CompanyRecurring.Recurring> list(@AuthenticationPrincipal AuthUser actor) {
		return this.service.list(actor);
	}

	@Operation(summary = "One repeat expense")
	@ApiResponse(responseCode = "404", description = "recurring_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/api/recurring/{id}")
	public CompanyRecurring.Recurring get(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.get(actor, id);
	}

	@Operation(summary = "Reminders waiting", description = "DUE reminders whose date has come in my time zone, oldest "
			+ "first, with the values to pre-fill a new transaction. overdue = the date is before my today.")
	@GetMapping("/api/recurring/due")
	public List<CompanyRecurring.Occurrence> due(@AuthenticationPrincipal AuthUser actor) {
		return this.service.due(actor);
	}

	@Operation(summary = "One reminder (any status)", description = "For the alert link: pre-fill values included.")
	@ApiResponse(responseCode = "404", description = "occurrence_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/api/recurring/occurrences/{id}")
	public CompanyRecurring.Occurrence occurrence(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.occurrence(actor, id);
	}

	@Operation(summary = "Add a repeat expense",
			description = "The first reminder is the first date of the series on or after my today.")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/recurring")
	public ResponseEntity<CompanyRecurring.Recurring> create(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody CompanyRecurring.RecurringRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.create(actor, body));
	}

	@Operation(summary = "Change a repeat expense", description = "Full replace. Reminders already due stay.")
	@PutMapping("/api/admin/recurring/{id}")
	public CompanyRecurring.Recurring update(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody CompanyRecurring.RecurringRequest body) {
		return this.service.update(actor, id, body);
	}

	@Operation(summary = "Pause (no new reminders)")
	@PostMapping("/api/admin/recurring/{id}/pause")
	public CompanyRecurring.Recurring pause(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.setPaused(actor, id, true);
	}

	@Operation(summary = "Resume", description = "Dates missed while paused are not reminded.")
	@PostMapping("/api/admin/recurring/{id}/resume")
	public CompanyRecurring.Recurring resume(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.setPaused(actor, id, false);
	}

	@Operation(summary = "Delete a repeat expense", description = "Its reminders go too; saved transactions stay.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	@DeleteMapping("/api/admin/recurring/{id}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		this.service.delete(actor, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Pause until a date", description = "No reminder before that date; then it goes on by "
			+ "itself. Resume ends it early. Body: {\"date\": \"2026-12-01\"} (after today).")
	@PostMapping("/api/admin/recurring/{id}/pause-until")
	public CompanyRecurring.Recurring pauseUntil(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody com.nordencapital.spine.common.Recurrence.DateRequest body) {
		return this.service.pauseUntil(actor, id, body.date());
	}

	@Operation(summary = "Skip a future date", description = "No reminder for that date; the next ones are normal. "
			+ "Body: {\"date\": \"2026-12-01\"} (a date of the series, today or later). 409 occurrence_exists when "
			+ "the reminder is already there (use Skip this time on it).")
	@PostMapping("/api/admin/recurring/{id}/skips")
	public CompanyRecurring.Recurring skipDate(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody com.nordencapital.spine.common.Recurrence.DateRequest body) {
		return this.service.skip(actor, id, body.date());
	}

	@Operation(summary = "Undo a skip", description = "Only before its date (409 too_late).")
	@DeleteMapping("/api/admin/recurring/{id}/skips/{date}")
	public CompanyRecurring.Recurring unskipDate(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@PathVariable String date) {
		return this.service.unskip(actor, id, date);
	}

	@Operation(summary = "Skip this time", description = "Closes a DUE reminder without a transaction.")
	@ApiResponse(responseCode = "409", description = "occurrence_not_due",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/recurring/occurrences/{id}/skip")
	public CompanyRecurring.Occurrence skip(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.skip(actor, id);
	}

}
