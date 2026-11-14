package com.nordencapital.spine.category;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.nordencapital.spine.common.ApiException;

/**
 * Icons a category may use (icon keys the frontend draws), in the order the frontend shows them.
 * "ellipsis" is for the seeded "Other" category. This list is the single source of truth:
 * GET /api/categories/icons returns it and validation uses it.
 */
public final class CategoryIcons {

	public static final List<String> ALLOWED = List.of(
			"plane", "car", "bus", "train-front", "fuel", "circle-parking", "hotel", "bed", "utensils", "coffee",
			"wine", "shopping-cart", "shopping-bag", "gift", "smartphone", "laptop", "monitor", "wifi", "package",
			"truck", "wrench", "briefcase", "book-open", "graduation-cap", "stethoscope", "heart-pulse", "dumbbell",
			"ticket", "film", "music", "receipt", "credit-card", "banknote", "landmark", "building-2", "house", "zap",
			"droplet", "file-text", "printer", "megaphone", "users", "tag", "globe", "map-pin", "shirt", "baby",
			"dog", "calendar",
			// Added for the bigger picker (same keys in the design kit).
			"server", "cloud", "cloud-services", "code", "code-file", "api", "database", "shield", "lock", "key",
			"subscription", "ai", "design", "palette", "devices", "keyboard", "headphones", "domain", "internet", "mail",
			"chat", "ads", "chart", "freelancer", "contractor", "salary", "tax", "calculator", "bank-fee", "course",
			"idea", "office", "folder", "archive", "map", "route", "cake", "client-payment", "invoice", "consulting",
			"sales", "refund", "investment", "coins", "award", "bonus",
			"ellipsis");

	private CategoryIcons() {
	}

	/**
	 * @throws ApiException 400 {@code validation_error} if the icon is not in the list
	 */
	public static void check(String icon) {
		if (!ALLOWED.contains(icon)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error",
					"icon: must be one of the icons from GET /api/categories/icons");
		}
	}

}
