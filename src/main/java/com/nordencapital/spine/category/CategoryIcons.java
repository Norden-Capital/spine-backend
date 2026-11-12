package com.nordencapital.spine.category;

import java.util.List;

/**
 * Icons a category may use (Lucide icon names), in the order the frontend shows them.
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
			"dog", "calendar", "ellipsis");

	private CategoryIcons() {
	}

}
