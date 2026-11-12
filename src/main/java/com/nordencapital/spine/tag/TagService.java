package com.nordencapital.spine.tag;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Names;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.User;

/**
 * Free tags. Any logged-in user can search and add tags; only managers (OWNER, ADMIN)
 * rename, archive and unarchive. Nothing is ever deleted.
 */
@Service
public class TagService {

	static final int MAX_RESULTS = 50;

	private final TagRepository tags;

	private final Actors actors;

	private final Clock clock;

	public TagService(TagRepository tags, Actors actors, Clock clock) {
		this.tags = tags;
		this.actors = actors;
		this.clock = clock;
	}

	/**
	 * Active tags whose name contains {@code text} (case does not count), by name, at
	 * most {@value #MAX_RESULTS}.
	 */
	@Transactional(readOnly = true)
	public List<TagResponse> search(AuthUser principal, String text) {
		this.actors.active(principal);
		String cleaned = (text != null) ? text.strip() : "";
		if (cleaned.length() > Names.MAX_LENGTH) {
			return List.of();
		}
		return this.tags.findTop50ByArchivedFalseAndNameContainingIgnoreCaseOrderByNameAsc(cleaned)
			.stream()
			.map(TagResponse::from)
			.toList();
	}

	/**
	 * Returns the active tag with this name (case does not count), or creates it.
	 * @param name already trimmed and checked (1 to 40 characters)
	 * @throws ApiException 409 {@code tag_archived} if an archived tag has this name
	 */
	@Transactional
	public Created create(AuthUser principal, String name) {
		User user = this.actors.active(principal);
		Optional<Tag> existing = this.tags.findByNameIgnoreCase(name);
		if (existing.isPresent()) {
			if (existing.get().isArchived()) {
				throw new ApiException(HttpStatus.CONFLICT, "tag_archived",
						"This tag exists but is archived. Ask an admin to unarchive it");
			}
			return new Created(TagResponse.from(existing.get()), false);
		}
		Tag tag = Tag.create(name, user.getId(), this.clock.instant());
		return new Created(TagResponse.from(saveFlush(tag)), true);
	}

	/** All tags, archived too (managers only). */
	@Transactional(readOnly = true)
	public List<AdminTagResponse> all(AuthUser principal) {
		this.actors.manager(principal);
		return this.tags.findAllByOrderByNameAsc().stream().map(AdminTagResponse::from).toList();
	}

	/**
	 * @throws ApiException 404 {@code tag_not_found}, 409 {@code name_taken}
	 */
	@Transactional
	public AdminTagResponse rename(AuthUser principal, UUID id, String name) {
		this.actors.manager(principal);
		Tag tag = find(id);
		boolean takenByOther = this.tags.findByNameIgnoreCase(name)
			.filter((other) -> !other.getId().equals(id))
			.isPresent();
		if (takenByOther) {
			throw nameTaken();
		}
		tag.rename(name, this.clock.instant());
		return AdminTagResponse.from(saveFlush(tag));
	}

	/**
	 * Archives or unarchives (no change if already in that state).
	 * @throws ApiException 404 {@code tag_not_found}
	 */
	@Transactional
	public AdminTagResponse setArchived(AuthUser principal, UUID id, boolean archived) {
		this.actors.manager(principal);
		Tag tag = find(id);
		if (tag.isArchived() != archived) {
			tag.setArchived(archived, this.clock.instant());
		}
		return AdminTagResponse.from(tag);
	}

	private Tag saveFlush(Tag tag) {
		try {
			return this.tags.saveAndFlush(tag);
		}
		catch (DataIntegrityViolationException ex) {
			// Someone saved the same name at the same moment.
			throw nameTaken();
		}
	}

	private Tag find(UUID id) {
		return this.tags.findById(id)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tag_not_found", "Tag not found"));
	}

	private static ApiException nameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "name_taken", "A tag with this name already exists");
	}

	/**
	 * @param created true if a new tag was made (201), false if an existing one was
	 * returned (200)
	 */
	public record Created(TagResponse tag, boolean created) {
	}

}
