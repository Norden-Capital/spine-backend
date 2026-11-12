package com.nordencapital.spine.file;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

	List<StoredFile> findAllByTransactionIdInOrderByCreatedAtAsc(Collection<UUID> transactionIds);

	List<StoredFile> findAllByTransactionIdIsNullAndWalletTransactionIdIsNullAndCreatedAtBefore(Instant before);

	List<StoredFile> findAllByWalletTransactionIdInOrderByCreatedAtAsc(Collection<UUID> walletTransactionIds);

}
