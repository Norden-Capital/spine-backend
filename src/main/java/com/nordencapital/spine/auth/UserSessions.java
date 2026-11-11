package com.nordencapital.spine.auth;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Logs a user out everywhere by deleting all their server-side sessions.
 */
@Component
public class UserSessions {

	private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions;

	public UserSessions(ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions) {
		this.sessions = sessions;
	}

	public void invalidateAll(UUID userId) {
		FindByIndexNameSessionRepository<? extends Session> repository = this.sessions.getIfAvailable();
		if (repository == null) {
			return;
		}
		repository.findByPrincipalName(userId.toString()).keySet().forEach(repository::deleteById);
	}

	/**
	 * Logs a user out on every other device: deletes all their sessions except
	 * {@code keepSessionId}.
	 */
	public void invalidateAllExcept(UUID userId, String keepSessionId) {
		FindByIndexNameSessionRepository<? extends Session> repository = this.sessions.getIfAvailable();
		if (repository == null) {
			return;
		}
		repository.findByPrincipalName(userId.toString())
			.keySet()
			.stream()
			.filter((id) -> !id.equals(keepSessionId))
			.forEach(repository::deleteById);
	}

}
