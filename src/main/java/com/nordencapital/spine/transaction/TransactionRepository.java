package com.nordencapital.spine.transaction;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

	/** The trash, last trashed first. */
	List<Transaction> findAllByDeletedAtIsNotNullOrderByDeletedAtDesc();

}
