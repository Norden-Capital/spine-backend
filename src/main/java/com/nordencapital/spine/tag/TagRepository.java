package com.nordencapital.spine.tag;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, UUID> {

	/** Spring Data escapes % and _ in {@code text}, so they are matched as plain characters. */
	List<Tag> findTop50ByArchivedFalseAndNameContainingIgnoreCaseOrderByNameAsc(String text);

	List<Tag> findAllByOrderByNameAsc();

	Optional<Tag> findByNameIgnoreCase(String name);

}
