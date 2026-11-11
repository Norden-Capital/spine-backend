package com.nordencapital.spine.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	List<User> findAllByOrderByCreatedAtAsc();

	/** The OWNER (there is at most one). */
	Optional<User> findFirstByRole(Role role);

}
