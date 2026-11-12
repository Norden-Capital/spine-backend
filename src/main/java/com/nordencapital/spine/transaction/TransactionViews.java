package com.nordencapital.spine.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.nordencapital.spine.category.Category;
import com.nordencapital.spine.category.CategoryRepository;
import com.nordencapital.spine.file.FileResponse;
import com.nordencapital.spine.file.StoredFile;
import com.nordencapital.spine.file.StoredFileRepository;
import com.nordencapital.spine.tag.Tag;
import com.nordencapital.spine.tag.TagRepository;
import com.nordencapital.spine.transaction.TransactionResponse.CategoryRef;
import com.nordencapital.spine.transaction.TransactionResponse.TagRef;
import com.nordencapital.spine.transaction.TransactionResponse.UserRef;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserRepository;

/**
 * Turns transactions into API responses. Loads users, categories, tags and files for all
 * given transactions at once (a few queries, not a few per row).
 */
@Component
class TransactionViews {

	private final UserRepository users;

	private final CategoryRepository categories;

	private final TagRepository tags;

	private final StoredFileRepository files;

	TransactionViews(UserRepository users, CategoryRepository categories, TagRepository tags,
			StoredFileRepository files) {
		this.users = users;
		this.categories = categories;
		this.tags = tags;
		this.files = files;
	}

	TransactionResponse one(Transaction transaction, User actor) {
		return many(List.of(transaction), actor).get(0);
	}

	List<TransactionResponse> many(List<Transaction> transactions, User actor) {
		Lookups lookups = lookups(transactions);
		return transactions.stream().map((tx) -> toResponse(tx, actor, lookups)).toList();
	}

	List<TrashedTransactionResponse> trashed(List<Transaction> transactions, User actor) {
		Lookups lookups = lookups(transactions);
		return transactions.stream()
			.map((tx) -> TrashedTransactionResponse.of(toResponse(tx, actor, lookups), tx.getDeletedAt(),
					userRef(lookups.users(), tx.getDeletedBy())))
			.toList();
	}

	/** Display names of a set of tags, sorted, as "a, b" (empty string if none). */
	String tagNames(Collection<UUID> tagIds) {
		return this.tags.findAllById(tagIds)
			.stream()
			.map(Tag::getName)
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.collect(Collectors.joining(", "));
	}

	/** Display names of files, in upload order, as "a.pdf, b.jpg" (empty string if none). */
	String fileNames(Collection<UUID> fileIds) {
		return this.files.findAllById(fileIds)
			.stream()
			.sorted(Comparator.comparing(StoredFile::getCreatedAt))
			.map(StoredFile::getOriginalName)
			.collect(Collectors.joining(", "));
	}

	String categoryName(UUID categoryId) {
		return this.categories.findById(categoryId).map(Category::getName).orElse(null);
	}

	static boolean canDelete(Transaction transaction, User actor) {
		return transaction.getCreatedBy().equals(actor.getId()) || isManager(actor);
	}

	static boolean isManager(User user) {
		return user.getRole() == Role.OWNER || user.getRole() == Role.ADMIN;
	}

	/** "1234.50" style money text. */
	static String money(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
	}

	private TransactionResponse toResponse(Transaction tx, User actor, Lookups lookups) {
		Category category = lookups.categories().get(tx.getCategoryId());
		CategoryRef categoryRef = (category != null) ? new CategoryRef(category.getId(), category.getName(),
				category.getIcon(), category.isArchived()) : null;
		List<TagRef> tagRefs = tx.getTagIds()
			.stream()
			.map(lookups.tags()::get)
			.filter(Objects::nonNull)
			.sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
			.map((tag) -> new TagRef(tag.getId(), tag.getName()))
			.toList();
		List<FileResponse> fileRefs = lookups.files().getOrDefault(tx.getId(), List.of());
		return new TransactionResponse(tx.getId(), tx.getType(), money(tx.getAmount()), tx.getCurrency(),
				tx.getDate(), tx.getDescription(), categoryRef, tagRefs, fileRefs,
				userRef(lookups.users(), tx.getCreatedBy()), tx.getCreatedAt(),
				userRef(lookups.users(), tx.getUpdatedBy()), tx.getUpdatedAt(), canDelete(tx, actor));
	}

	static UserRef userRef(Map<UUID, User> users, UUID id) {
		if (id == null) {
			return null;
		}
		User user = users.get(id);
		return new UserRef(id, (user != null) ? user.displayName() : null);
	}

	private Lookups lookups(List<Transaction> transactions) {
		Set<UUID> userIds = new HashSet<>();
		Set<UUID> categoryIds = new HashSet<>();
		Set<UUID> tagIds = new HashSet<>();
		Set<UUID> transactionIds = new HashSet<>();
		for (Transaction tx : transactions) {
			Stream.of(tx.getCreatedBy(), tx.getUpdatedBy(), tx.getDeletedBy())
				.filter(Objects::nonNull)
				.forEach(userIds::add);
			categoryIds.add(tx.getCategoryId());
			tagIds.addAll(tx.getTagIds());
			transactionIds.add(tx.getId());
		}
		Map<UUID, List<FileResponse>> filesByTransaction = transactionIds.isEmpty() ? Map.of()
				: this.files.findAllByTransactionIdInOrderByCreatedAtAsc(transactionIds)
					.stream()
					.collect(Collectors.groupingBy(StoredFile::getTransactionId,
							Collectors.mapping(FileResponse::from, Collectors.toList())));
		return new Lookups(byId(this.users.findAllById(userIds), User::getId),
				byId(this.categories.findAllById(categoryIds), Category::getId),
				byId(this.tags.findAllById(tagIds), Tag::getId), filesByTransaction);
	}

	private static <T> Map<UUID, T> byId(List<T> items, Function<T, UUID> id) {
		return items.stream().collect(Collectors.toMap(id, Function.identity()));
	}

	Map<UUID, User> usersById(Collection<UUID> ids) {
		return byId(this.users.findAllById(ids), User::getId);
	}

	private record Lookups(Map<UUID, User> users, Map<UUID, Category> categories, Map<UUID, Tag> tags,
			Map<UUID, List<FileResponse>> files) {
	}

}
