package com.nordencapital.spine.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	List<User> findAllByOrderByCreatedAtAsc();

	boolean existsByRoleAndStatus(Role role, UserStatus status);

	/**
	 * Locks all active admins, so two admins cannot demote each other at the same time.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<User> findAllByRoleAndStatus(Role role, UserStatus status);

}
