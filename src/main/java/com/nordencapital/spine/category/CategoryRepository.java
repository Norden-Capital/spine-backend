package com.nordencapital.spine.category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

	Optional<Category> findByNameIgnoreCase(String name);

	List<Category> findAllByOrderByNameAsc();

	boolean existsByParentId(UUID parentId);


}
