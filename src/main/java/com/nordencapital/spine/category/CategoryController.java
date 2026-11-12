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
import com.nordencapital.spine.common.ParentPatch;
import com.nordencapital.spine.common.Websites;
import tools.jackson.databind.JsonNode;

@Tag(name = "Categories", description = "Everyone reads active categories. /api/admin/categories is for the "
		+ "OWNER. Categories are never deleted, only archived.")
@RestController
public class CategoryController {

	private final CategoryService service;

	public CategoryController(CategoryService service) {
		this.service = service;
	}

	@Operation(summary = "Categories for a picker",
			description = "Active ones (and not under an archived parent), most used in the last 90 days first, then by "
					+ "name. Flat list: group children by parentId.")
	@GetMapping("/api/categories")
	public List<CategoryPick> active(@AuthenticationPrincipal AuthUser actor,
			@io.swagger.v3.oas.annotations.Parameter(description = "EXPENSE or INCOME (left out = both)")
			@org.springframework.web.bind.annotation.RequestParam(required = false) String kind) {
		return this.service.active(actor, kind);
	}

	@Operation(summary = "Icons a category can use", description = "Lucide icon names.")
	@GetMapping("/api/categories/icons")
	public List<String> icons() {
		return CategoryIcons.ALLOWED;
	}

	@Operation(summary = "All categories, archived too",
			description = "Manage-page order: each main category by position, followed by its subcategories by "
					+ "position.")
	@GetMapping("/api/admin/categories")
	public List<CategoryResponse> all(@AuthenticationPrincipal AuthUser actor,
			@io.swagger.v3.oas.annotations.Parameter(description = "EXPENSE or INCOME (left out = both)")
			@org.springframework.web.bind.annotation.RequestParam(required = false) String kind) {
		return this.service.all(actor, kind);
	}

	@Operation(summary = "Create a category",
			description = "Name: 1 to 40 characters, unique (case does not count). parentId: a top-level category, or "
					+ "null (one level only).")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "name_taken",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/categories")
	public ResponseEntity<CategoryResponse> create(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody CreateRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.create(actor, body.name(), body.icon(), body.parentId(),
				body.website(), body.kind()));
	}

	@Operation(summary = "Change the name, icon and/or parent",
			description = "name/icon: leave out (or null) to keep. parentId: leave out to keep, null = top level, an "
					+ "id = move under that category. website: leave out to keep, null or \"\" = remove.")
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
		return this.service.update(actor, id, body.name(), body.icon(), ParentPatch.of(body.parentId()),
				Websites.patch(body.website()), body.kind());
	}

	@Operation(summary = "Move a category (drag and drop)",
			description = "parentId: null = main category, or a main category id (one level only). position: 0-based "
					+ "among the new siblings (too big = last). Siblings are renumbered. Archived ones can move too. "
					+ "Returns ALL categories in manage-page order.")
	@ApiResponse(responseCode = "200", description = "Moved")
	@ApiResponse(responseCode = "400", description = "validation_error (own parent, under a subcategory, or it has "
			+ "subcategories)", content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "category_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PatchMapping("/api/admin/categories/{id}/move")
	public List<CategoryResponse> move(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody MoveRequest body) {
		return this.service.move(actor, id, body.parentId(), body.position());
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

	/**
	 * @param parentId null or left out = main category
	 * @param position 0-based place among the new siblings
	 */
	public record MoveRequest(@Schema(nullable = true) UUID parentId,
			@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Integer position) {
	}

	/** The name is trimmed before validation. */
	public record CreateRequest(
			@NotBlank @Size(max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@Schema(description = "One of GET /api/categories/icons") @NotNull String icon,
			@Schema(nullable = true, description = "A top-level category, or null") UUID parentId,
			@Schema(nullable = true, description = "Optional website (logo), e.g. \"claude.ai\"; stored as the bare "
					+ "domain") @Size(max = Websites.MAX_INPUT) String website,
			@Schema(nullable = true, allowableValues = { "EXPENSE", "INCOME" },
					description = "Main category: default EXPENSE. Subcategory: always its main's kind (left out = "
							+ "that kind)") String kind) {

		public CreateRequest {
			name = Names.clean(name);
		}

	}

	/** Both fields optional; the name is trimmed before validation. */
	public record UpdateRequest(
			@Schema(nullable = true) @Size(min = 1, max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@Schema(nullable = true, description = "One of GET /api/categories/icons") String icon,
			@Schema(nullable = true, type = "string", format = "uuid",
					description = "Leave out = keep; null = top level; an id = move under it") JsonNode parentId,
			@Schema(nullable = true, type = "string",
					description = "Leave out = keep; null or \"\" = remove; text = set") JsonNode website,
			@Schema(nullable = true, allowableValues = { "EXPENSE", "INCOME" },
					description = "Main categories only (its subcategories follow). Leave out = keep") String kind) {

		public UpdateRequest {
			name = Names.clean(name);
		}

	}

}
