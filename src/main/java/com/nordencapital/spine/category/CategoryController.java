package com.nordencapital.spine.category;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;
import com.nordencapital.spine.common.Names;

@Tag(name = "Categories", description = "Everyone reads active categories. /api/admin/categories is for OWNER and "
		+ "ADMIN. Categories are never deleted, only archived.")
@RestController
public class CategoryController {

	private final CategoryService service;

	public CategoryController(CategoryService service) {
		this.service = service;
	}

	@Operation(summary = "Active categories", description = "Sorted for display (Other stays last).")
	@GetMapping("/api/categories")
	public List<CategoryResponse> active(@AuthenticationPrincipal AuthUser actor) {
		return this.service.active(actor);
	}

	@Operation(summary = "Icons a category can use", description = "Lucide icon names.")
	@GetMapping("/api/categories/icons")
	public List<String> icons() {
		return CategoryIcons.ALLOWED;
	}

	@Operation(summary = "All categories, archived too")
	@GetMapping("/api/admin/categories")
	public List<CategoryResponse> all(@AuthenticationPrincipal AuthUser actor) {
		return this.service.all(actor);
	}

	@Operation(summary = "Create a category", description = "Name: 1 to 40 characters, unique (case does not count).")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "name_taken",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/categories")
	public ResponseEntity<CategoryResponse> create(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody CreateRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.create(actor, body.name(), body.icon()));
	}

	@Operation(summary = "Rename and/or change the icon", description = "Leave a field out (or null) to keep it.")
	@ApiResponse(responseCode = "200", description = "Saved")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "category_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "name_taken",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PatchMapping("/api/admin/categories/{id}")
	public CategoryResponse update(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody UpdateRequest body) {
		return this.service.update(actor, id, body.name(), body.icon());
	}

	@Operation(summary = "Archive a category", description = "Hidden from GET /api/categories. Old data keeps it.")
	@ApiResponse(responseCode = "200", description = "Archived")
	@ApiResponse(responseCode = "404", description = "category_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/categories/{id}/archive")
	public CategoryResponse archive(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.setArchived(actor, id, true);
	}

	@Operation(summary = "Unarchive a category")
	@ApiResponse(responseCode = "200", description = "Active again")
	@ApiResponse(responseCode = "404", description = "category_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/categories/{id}/unarchive")
	public CategoryResponse unarchive(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.setArchived(actor, id, false);
	}

	/** The name is trimmed before validation. */
	public record CreateRequest(
			@NotBlank @Size(max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@Schema(description = "One of GET /api/categories/icons") @NotNull String icon) {

		public CreateRequest {
			name = Names.clean(name);
		}

	}

	/** Both fields optional; the name is trimmed before validation. */
	public record UpdateRequest(
			@Schema(nullable = true) @Size(min = 1, max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@Schema(nullable = true, description = "One of GET /api/categories/icons") String icon) {

		public UpdateRequest {
			name = Names.clean(name);
		}

	}

}
