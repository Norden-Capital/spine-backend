package com.nordencapital.spine.transaction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.category.Category;
import com.nordencapital.spine.category.CategoryRepository;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.file.StoredFile;
import com.nordencapital.spine.file.StoredFileRepository;
import com.nordencapital.spine.me.UserZones;
import com.nordencapital.spine.tag.Tag;
import com.nordencapital.spine.tag.TagRepository;
import com.nordencapital.spine.transaction.HistoryEntry.Change;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.User;

/**
 * Company transactions. Shared: every logged-in user sees and edits all of them.
 * <ul>
 * <li>Every transaction needs 1 to 5 proof files (uploaded first, then attached).</li>
 * <li>Trash (soft delete): its creator, OWNER or ADMIN. Restore: OWNER or ADMIN.</li>
 * <li>Every create, real change, delete and restore is written to the history.</li>
 * </ul>
 */
@Service
public class TransactionService {

	static final int MAX_TAGS = 10;

	static final int MAX_FILES = 5;

	static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

	/**
	 * The latest time zone on earth. With an "automatic" time zone we do not know the
	 * user's day, so "future" means after today here (never refuses a real today).
	 */
	private static final ZoneId LATEST_ZONE = ZoneOffset.ofHours(14);

	private final TransactionRepository transactions;

	private final CategoryRepository categories;

	private final TagRepository tags;

	private final StoredFileRepository files;

	private final TransactionViews views;

	private final TransactionHistory history;

	private final Actors actors;

	private final UserZones zones;

	private final Clock clock;

	public TransactionService(TransactionRepository transactions, CategoryRepository categories, TagRepository tags,
			StoredFileRepository files, TransactionViews views, TransactionHistory history, Actors actors,
			UserZones zones, Clock clock) {
		this.transactions = transactions;
		this.categories = categories;
		this.tags = tags;
		this.files = files;
		this.views = views;
		this.history = history;
		this.actors = actors;
		this.zones = zones;
		this.clock = clock;
	}

	/**
	 * One month, not trashed, newest first, with totals per currency.
	 * @param month "YYYY-MM"; null = this month in my time zone preference (UTC if
	 * automatic)
	 * @throws ApiException 400 {@code validation_error} (bad month)
	 */
	@Transactional(readOnly = true)
	public TransactionList month(AuthUser principal, String month) {
		User actor = this.actors.active(principal);
		YearMonth yearMonth = (month != null) ? parseMonth(month)
				: YearMonth.now(this.clock.withZone(this.zones.saved(actor.getId()).orElse(ZoneOffset.UTC)));
		List<Transaction> found = this.transactions.findAllByDeletedAtIsNullAndDateBetweenOrderByDateDescCreatedAtDesc(
				yearMonth.atDay(1), yearMonth.atEndOfMonth());
		return new TransactionList(this.views.many(found, actor), totals(found));
	}

	/**
	 * @throws ApiException 404 {@code transaction_not_found} (also when trashed)
	 */
	@Transactional(readOnly = true)
	public TransactionResponse get(AuthUser principal, UUID id) {
		User actor = this.actors.active(principal);
		return this.views.one(active(id), actor);
	}

	/**
	 * @throws ApiException 400 {@code validation_error}, {@code proof_required},
	 * {@code future_date}
	 */
	@Transactional
	public TransactionResponse create(AuthUser principal, TransactionRequest request) {
		User actor = this.actors.active(principal);
		Checked checked = check(request, actor, null);
		Instant now = this.clock.instant();
		Transaction transaction = this.transactions.saveAndFlush(Transaction.create(checked.values(), actor.getId(), now));
		checked.files().forEach((file) -> file.attachTo(transaction.getId()));
		this.history.record(transaction.getId(), actor.getId(), TransactionHistory.CREATED,
				diff(Snapshot.EMPTY, snapshot(checked)), now);
		return this.views.one(transaction, actor);
	}

	/**
	 * Replaces all fields. Files left out are detached (and deleted later by the
	 * cleanup job). Without a real change nothing is saved and no history is written.
	 * @throws ApiException 404 {@code transaction_not_found}, 400 {@code validation_error},
	 * {@code proof_required}, {@code future_date}
	 */
	@Transactional
	public TransactionResponse update(AuthUser principal, UUID id, TransactionRequest request) {
		User actor = this.actors.active(principal);
		Transaction transaction = active(id);
		Checked checked = check(request, actor, transaction);
		List<StoredFile> currentFiles = this.files.findAllByTransactionIdInOrderByCreatedAtAsc(Set.of(id));
		Snapshot before = snapshot(transaction, currentFiles);
		List<Change> changes = diff(before, snapshot(checked));
		if (changes.isEmpty()) {
			return this.views.one(transaction, actor);
		}
		Instant now = this.clock.instant();
		transaction.update(checked.values(), actor.getId(), now);
		Set<UUID> keep = checked.files().stream().map(StoredFile::getId).collect(Collectors.toSet());
		currentFiles.stream().filter((file) -> !keep.contains(file.getId())).forEach(StoredFile::detach);
		checked.files().forEach((file) -> file.attachTo(id));
		this.transactions.flush();
		this.history.record(id, actor.getId(), TransactionHistory.UPDATED, changes, now);
		return this.views.one(transaction, actor);
	}

	/**
	 * Moves to the trash.
	 * @throws ApiException 404 {@code transaction_not_found}, 403 {@code forbidden} (not
	 * its creator, not OWNER or ADMIN)
	 */
	@Transactional
	public void trash(AuthUser principal, UUID id) {
		User actor = this.actors.active(principal);
		Transaction transaction = active(id);
		if (!TransactionViews.canDelete(transaction, actor)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
					"Only the person who added it, an admin or the owner can delete it");
		}
		Instant now = this.clock.instant();
		transaction.trash(actor.getId(), now);
		this.history.record(id, actor.getId(), TransactionHistory.DELETED, List.of(), now);
	}

	/** The trash (OWNER and ADMIN), last trashed first. */
	@Transactional(readOnly = true)
	public List<TrashedTransactionResponse> trashList(AuthUser principal) {
		User actor = this.actors.manager(principal);
		return this.views.trashed(this.transactions.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc(), actor);
	}

	/**
	 * Takes a transaction out of the trash (OWNER and ADMIN). Not in the trash: no change.
	 * @throws ApiException 404 {@code transaction_not_found}
	 */
	@Transactional
	public TransactionResponse restore(AuthUser principal, UUID id) {
		User actor = this.actors.manager(principal);
		Transaction transaction = this.transactions.findById(id).orElseThrow(TransactionService::notFound);
		if (transaction.isTrashed()) {
			Instant now = this.clock.instant();
			transaction.restore();
			this.history.record(id, actor.getId(), TransactionHistory.RESTORED, List.of(), now);
		}
		return this.views.one(transaction, actor);
	}

	/**
	 * History, newest first. Trashed transactions: OWNER and ADMIN only.
	 * @throws ApiException 404 {@code transaction_not_found}
	 */
	@Transactional(readOnly = true)
	public List<HistoryEntry> history(AuthUser principal, UUID id) {
		User actor = this.actors.active(principal);
		Transaction transaction = this.transactions.findById(id)
			.filter((tx) -> !tx.isTrashed() || TransactionViews.isManager(actor))
			.orElseThrow(TransactionService::notFound);
		return this.history.list(transaction.getId(), this.views.usersById(this.history.userIds(id)));
	}

	// ---- checks ----

	private Checked check(TransactionRequest request, User actor, Transaction existing) {
		BigDecimal amount = new BigDecimal(request.amount());
		if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
			throw invalid("amount: must be more than 0 and at most 9999999999.99");
		}
		LocalDate date = parseDate(request.date());
		ZoneId zone = this.zones.saved(actor.getId()).orElse(LATEST_ZONE);
		if (date.isAfter(LocalDate.now(this.clock.withZone(zone)))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "future_date", "The date cannot be in the future");
		}

		Set<UUID> fileIds = distinct(request.fileIds());
		if (fileIds.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "proof_required",
					"Add at least one proof file (photo or PDF)");
		}
		if (fileIds.size() > MAX_FILES) {
			throw invalid("fileIds: at most " + MAX_FILES + " files");
		}
		Set<UUID> tagIds = distinct(request.tagIds());
		if (tagIds.size() > MAX_TAGS) {
			throw invalid("tagIds: at most " + MAX_TAGS + " tags");
		}

		Category category = this.categories.findById(request.categoryId())
			.orElseThrow(() -> invalid("categoryId: unknown category"));
		boolean keepsOldCategory = existing != null && existing.getCategoryId().equals(category.getId());
		if (category.isArchived() && !keepsOldCategory) {
			throw invalid("categoryId: this category is archived");
		}

		Set<UUID> oldTags = (existing != null) ? existing.getTagIds() : Set.of();
		List<Tag> foundTags = this.tags.findAllById(tagIds);
		if (foundTags.size() != tagIds.size()) {
			throw invalid("tagIds: unknown tag");
		}
		if (foundTags.stream().anyMatch((tag) -> tag.isArchived() && !oldTags.contains(tag.getId()))) {
			throw invalid("tagIds: a tag is archived");
		}

		List<StoredFile> foundFiles = this.files.findAllById(fileIds);
		UUID existingId = (existing != null) ? existing.getId() : null;
		boolean allUsable = foundFiles.size() == fileIds.size() && foundFiles.stream()
			.allMatch((file) -> (file.getTransactionId() == null && file.getOwnerId().equals(actor.getId()))
					|| (existingId != null && existingId.equals(file.getTransactionId())));
		if (!allUsable) {
			throw invalid("fileIds: use files you just uploaded, or files already on this transaction");
		}
		foundFiles = new ArrayList<>(foundFiles);
		foundFiles.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));

		Transaction.Values values = new Transaction.Values(request.type(), amount.setScale(2), request.currency(),
				date, request.description(), category.getId(), tagIds);
		return new Checked(values, category, foundTags, foundFiles);
	}

	private static Set<UUID> distinct(List<UUID> ids) {
		return (ids != null) ? ids.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new))
				: Set.of();
	}

	private static LocalDate parseDate(String date) {
		try {
			return LocalDate.parse(date);
		}
		catch (DateTimeParseException ex) {
			throw invalid("date: must be a real date like 2026-03-31");
		}
	}

	private static YearMonth parseMonth(String month) {
		try {
			return YearMonth.parse(month);
		}
		catch (DateTimeParseException ex) {
			throw invalid("month: must be like 2026-03");
		}
	}

	// ---- totals and history ----

	private static Map<String, TransactionList.Totals> totals(List<Transaction> found) {
		Map<String, TransactionList.Totals> totals = new LinkedHashMap<>();
		for (String currency : List.of("USD", "MAD")) {
			BigDecimal in = sum(found, currency, "INCOME");
			BigDecimal out = sum(found, currency, "EXPENSE");
			totals.put(currency, new TransactionList.Totals(TransactionViews.money(in), TransactionViews.money(out),
					TransactionViews.money(in.subtract(out))));
		}
		return totals;
	}

	private static BigDecimal sum(List<Transaction> found, String currency, String type) {
		return found.stream()
			.filter((tx) -> tx.getCurrency().equals(currency) && tx.getType().equals(type))
			.map(Transaction::getAmount)
			.reduce(BigDecimal.ZERO, BigDecimal::add)
			.setScale(2);
	}

	private Snapshot snapshot(Transaction tx, List<StoredFile> currentFiles) {
		return new Snapshot(tx.getType(), TransactionViews.money(tx.getAmount()), tx.getCurrency(),
				tx.getDate().toString(), tx.getDescription(), tx.getCategoryId(),
				this.views.categoryName(tx.getCategoryId()), tx.getTagIds(), this.views.tagNames(tx.getTagIds()),
				currentFiles.stream().map(StoredFile::getId).collect(Collectors.toSet()),
				currentFiles.stream().map(StoredFile::getOriginalName).collect(Collectors.joining(", ")));
	}

	private static Snapshot snapshot(Checked checked) {
		Transaction.Values v = checked.values();
		return new Snapshot(v.type(), TransactionViews.money(v.amount()), v.currency(), v.date().toString(),
				v.description(), checked.category().getId(), checked.category().getName(), v.tagIds(),
				checked.tags()
					.stream()
					.map(Tag::getName)
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.collect(Collectors.joining(", ")),
				checked.files().stream().map(StoredFile::getId).collect(Collectors.toSet()),
				checked.files().stream().map(StoredFile::getOriginalName).collect(Collectors.joining(", ")));
	}

	/** Only fields that really changed. Tags and files compare by id, show names. */
	private static List<Change> diff(Snapshot before, Snapshot after) {
		List<Change> changes = new ArrayList<>();
		add(changes, "type", before.type(), after.type(), Objects.equals(before.type(), after.type()));
		add(changes, "amount", before.amount(), after.amount(), Objects.equals(before.amount(), after.amount()));
		add(changes, "currency", before.currency(), after.currency(),
				Objects.equals(before.currency(), after.currency()));
		add(changes, "date", before.date(), after.date(), Objects.equals(before.date(), after.date()));
		add(changes, "description", before.description(), after.description(),
				Objects.equals(before.description(), after.description()));
		add(changes, "category", before.categoryName(), after.categoryName(),
				Objects.equals(before.categoryId(), after.categoryId()));
		add(changes, "tags", before.tagNames(), after.tagNames(), Objects.equals(before.tagIds(), after.tagIds()));
		add(changes, "files", before.fileNames(), after.fileNames(), Objects.equals(before.fileIds(), after.fileIds()));
		return changes;
	}

	private static void add(List<Change> changes, String field, String from, String to, boolean same) {
		if (!same) {
			changes.add(new Change(field, emptyToNull(from), emptyToNull(to)));
		}
	}

	private static String emptyToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	private Transaction active(UUID id) {
		return this.transactions.findById(id).filter((tx) -> !tx.isTrashed()).orElseThrow(TransactionService::notFound);
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "transaction_not_found", "Transaction not found");
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

	private record Checked(Transaction.Values values, Category category, List<Tag> tags, List<StoredFile> files) {
	}

	/** Display values of all fields, to compare before and after. */
	private record Snapshot(String type, String amount, String currency, String date, String description,
			UUID categoryId, String categoryName, Set<UUID> tagIds, String tagNames, Set<UUID> fileIds,
			String fileNames) {

		static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null, null, Set.of(), "", Set.of(),
				"");

	}

}
