package com.nordencapital.spine.category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

	List<Category> findAllByArchivedFalseOrderBySortOrderAscNameAsc();

	List<Category> findAllByOrderBySortOrderAscNameAsc();

	Optional<Category> findByNameIgnoreCase(String name);

	List<Category> findAllByOrderByNameAsc();

	boolean existsByParentId(UUID parentId);

	/** Highest sort order below the "last" ones (like Other), or null if none. */
	@Query("SELECT max(c.sortOrder) FROM Category c WHERE c.sortOrder < :limit")
	Integer maxSortOrderBelow(int limit);

}
