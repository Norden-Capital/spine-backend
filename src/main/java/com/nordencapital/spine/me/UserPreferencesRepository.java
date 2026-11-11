package com.nordencapital.spine.me;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {

}
