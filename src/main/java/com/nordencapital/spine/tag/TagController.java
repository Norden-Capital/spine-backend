package com.nordencapital.spine.tag;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;
import com.nordencapital.spine.common.Names;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Tags", description = "Everyone can search and add tags. "
		+ "/api/admin/tags is for OWNER and ADMIN. Tags are never deleted, only archived.")
@RestController
public class TagController {

	private final TagService service;

	public TagController(TagService service) {
		this.service = service;
	}

	@Operation(summary = "Search active tags", description = "Name contains q (case does not count), by name, max 50.")
	@GetMapping("/api/tags")
	public List<TagPick> search(@AuthenticationPrincipal AuthUser actor,
			@Parameter(description = "Text to look for. Empty = all active tags.") @RequestParam(
					required = false) String q) {
		return this.service.search(actor, q);
	}

	@Operation(summary = "Add a tag",
			description = "If an active tag with this name exists (case does not count), it is returned (200).")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "200", description = "Already exists; the existing tag")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "tag_archived",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/tags")
	public ResponseEntity<TagResponse> create(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody NameRequest body) {
		TagService.Created result = this.service.create(actor, body.name());
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.tag());
	}

	@Operation(summary = "All tags, archived too")
	@GetMapping("/api/admin/tags")
	public List<AdminTagResponse> all(@AuthenticationPrincipal AuthUser actor) {
		return this.service.all(actor);
	}

	@Operation(summary = "Rename a tag")
	@ApiResponse(responseCode = "200", description = "Saved")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "tag_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "name_taken",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PatchMapping("/api/admin/tags/{id}")
	public AdminTagResponse rename(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody NameRequest body) {
		return this.service.rename(actor, id, body.name());
	}

	@Operation(summary = "Archive a tag", description = "Hidden from search and cannot be added again. Old data keeps it.")
	@ApiResponse(responseCode = "200", description = "Archived")
	@ApiResponse(responseCode = "404", description = "tag_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/tags/{id}/archive")
	public AdminTagResponse archive(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.setArchived(actor, id, true);
	}

	@Operation(summary = "Unarchive a tag")
	@ApiResponse(responseCode = "200", description = "Active again")
	@ApiResponse(responseCode = "404", description = "tag_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/tags/{id}/unarchive")
	public AdminTagResponse unarchive(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.setArchived(actor, id, false);
	}

	/** The name is trimmed before validation. */
	public record NameRequest(@NotBlank @Size(max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
			message = "must not contain control characters") String name) {

		public NameRequest {
			name = Names.clean(name);
		}

	}

}
