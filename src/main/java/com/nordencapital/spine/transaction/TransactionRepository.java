package com.nordencapital.spine.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

	/** Not trashed, dates from {@code from} to {@code to} (both included), newest first. */
	List<Transaction> findAllByDeletedAtIsNullAndDateBetweenOrderByDateDescCreatedAtDesc(LocalDate from, LocalDate to);

	/** The trash, last trashed first. */
	List<Transaction> findAllByDeletedAtIsNotNullOrderByDeletedAtDesc();

}
