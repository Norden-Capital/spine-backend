package com.nordencapital.spine.common;

import org.springframework.http.HttpStatus;

/**
 * Kinds of categories: EXPENSE or INCOME. A subcategory always has the kind of its main
 * category, and a transaction (or repeat template) must use a category of its own type.
 */
public final class CategoryKinds {

	public static final String EXPENSE = "EXPENSE";

	public static final String INCOME = "INCOME";

	public static final String PATTERN = "EXPENSE|INCOME";

	private CategoryKinds() {
	}

	/**
	 * @return EXPENSE, INCOME or null (not given)
	 * @throws ApiException 400 {@code validation_error}
	 */
	public static String parse(String field, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		if (!value.equals(EXPENSE) && !value.equals(INCOME)) {
			throw invalid(field + ": must be EXPENSE or INCOME");
		}
		return value;
	}

	/**
	 * @throws ApiException 400 {@code validation_error} if the category does not fit the
	 * transaction type
	 */
	public static void checkFits(String categoryKind, String transactionType) {
		if (categoryKind != null && !categoryKind.equals(transactionType)) {
			throw invalid("categoryId: this is " + word(categoryKind) + " category; pick " + word(transactionType)
					+ " category for " + word(transactionType));
		}
	}

	/**
	 * @throws ApiException 400 {@code validation_error} if a category of {@code kind}
	 * cannot go under a main category of {@code parentKind}
	 */
	public static void checkParent(String parentKind, String kind) {
		if (!parentKind.equals(kind)) {
			throw invalid("parentId: the main category is " + word(parentKind)
					+ " category; a subcategory always has the kind of its main category");
		}
	}

	/** @throws ApiException 400 when a subcategory is given another kind than its main */
	public static ApiException subKind() {
		return invalid("kind: a subcategory always has the kind of its main category (change the main category)");
	}

	private static String word(String kind) {
		return INCOME.equals(kind) ? "an income" : "an expense";
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

}
