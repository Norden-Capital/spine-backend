package com.nordencapital.spine.transaction;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.nordencapital.spine.common.Names;

/**
 * Body of POST and PUT /api/transactions (PUT replaces everything). The description is
 * trimmed before validation. Proof files and the date are checked in the service
 * (proof_required, future_date).
 */
public record TransactionRequest(
		@Schema(allowableValues = { "EXPENSE", "INCOME" }) @NotNull
		@Pattern(regexp = "EXPENSE|INCOME", message = "must be EXPENSE or INCOME") String type,
		@Schema(description = "Money as text, max 2 decimals, e.g. \"123.45\"", example = "123.45") @NotNull
		@Pattern(regexp = "(0|[1-9][0-9]{0,9})(\\.[0-9]{1,2})?",
				message = "must be an amount like 123.45 (max 2 decimals)") String amount,
		@Schema(allowableValues = { "USD", "MAD" }) @NotNull
		@Pattern(regexp = "USD|MAD", message = "must be USD or MAD") String currency,
		@Schema(description = "YYYY-MM-DD, not in the future", example = "2026-03-31") @NotNull
		@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31") String date,
		@NotBlank @Size(max = 200) @Pattern(regexp = Names.NO_CONTROL_CHARS,
				message = "must not contain control characters") String description,
		@Schema(description = "An active category (an archived one can stay on an old transaction)") @NotNull
		UUID categoryId,
		@Schema(description = "0 to 10 active tags (archived ones already on it can stay)", nullable = true)
		@Size(max = TransactionService.MAX_TAGS) List<UUID> tagIds,
		@Schema(description = "1 to 5 proof files: your new uploads, or files already on it") List<UUID> fileIds,
		@Schema(description = "Create only: true = save even if a transaction with the same type, amount, currency "
				+ "and date exists", nullable = true) Boolean confirmDuplicate) {

	public TransactionRequest {
		description = (description != null) ? description.strip() : null;
	}

}
