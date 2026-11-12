package com.nordencapital.spine.passkey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PasskeyRepository extends JpaRepository<Passkey, UUID> {

	List<Passkey> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

	Optional<Passkey> findByCredentialId(byte[] credentialId);

	Optional<Passkey> findByIdAndUserId(UUID id, UUID userId);

	long countByUserId(UUID userId);

}
