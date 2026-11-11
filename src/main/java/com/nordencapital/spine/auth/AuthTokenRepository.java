package com.nordencapital.spine.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

	Optional<AuthToken> findByTokenHash(String tokenHash);

	boolean existsByUserIdAndPurposeAndExpiresAtAfter(UUID userId, TokenPurpose purpose, Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = false)
	@Query("delete from AuthToken t where t.user.id = :userId")
	int deleteAllByUserId(@Param("userId") UUID userId);

}
