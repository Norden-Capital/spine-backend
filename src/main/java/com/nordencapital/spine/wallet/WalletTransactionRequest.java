package com.nordencapital.spine.wallet;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.nordencapital.spine.common.Names;
import com.nordencapital.spine.common.Websites;

/**
 * Body of POST and PUT /api/wallet/transactions (PUT replaces everything). Like the company
 * transaction body, plus {@code currency}. The description is trimmed before validation.
 */
public record WalletTransactionRequest(
		@Schema(allowableValues = { "EXPENSE", "INCOME" }) @NotNull
		@Pattern(regexp = "EXPENSE|INCOME", message = "must be EXPENSE or INCOME") String type,
		@Schema(description = "Amount in `currency` as text, max 2 decimals, e.g. \"123.45\"", example = "123.45")
		@NotNull @Pattern(regexp = "(0|[1-9][0-9]{0,9})(\\.[0-9]{1,2})?",
				message = "must be an amount like 123.45 (max 2 decimals)") String amount,
		@Schema(allowableValues = { "USD", "MAD" }, nullable = true,
				description = "Create: left out = my default wallet currency. Change: left out = keep.")
		@Pattern(regexp = "USD|MAD", message = "must be USD or MAD") String currency,
		@Schema(description = "YYYY-MM-DD, not in the future", example = "2026-03-31") @NotNull
		@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31") String date,
		@NotBlank @Size(max = 200) @Pattern(regexp = Names.NO_CONTROL_CHARS,
				message = "must not contain control characters") String description,
		@Schema(description = "An active category (an archived one can stay on an old transaction)") @NotNull
		UUID categoryId,
		@Schema(description = "0 to 5 files: your new uploads, or files already on it") List<UUID> fileIds,
		@Schema(description = "Optional website, e.g. \"claude.ai\". Stored as the bare domain. Empty or null = none.",
				nullable = true, example = "claude.ai")
		@Size(max = Websites.MAX_INPUT) String website,
		@Schema(description = "Create only: true = save even if one of my transactions has the same type, amount, "
				+ "currency and date", nullable = true) Boolean confirmDuplicate) {

	public WalletTransactionRequest {
		description = (description != null) ? description.strip() : null;
	}

}
