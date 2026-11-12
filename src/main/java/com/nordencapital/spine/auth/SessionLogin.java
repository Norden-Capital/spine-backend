package com.nordencapital.spine.auth;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

import com.nordencapital.spine.user.User;

/**
 * Starts the logged-in session, the same way for password and passkey login: new
 * session id (stops session fixation), user saved in the session, new CSRF token.
 */
@Component
public class SessionLogin {

	private final SecurityContextRepository securityContextRepository;

	private final CsrfTokenRepository csrfTokenRepository;

	private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder
		.getContextHolderStrategy();

	public SessionLogin(SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository) {
		this.securityContextRepository = securityContextRepository;
		this.csrfTokenRepository = csrfTokenRepository;
	}

	public void start(User loggedIn, HttpServletRequest request, HttpServletResponse response) {
		AuthUser user = new AuthUser(loggedIn.getId(), loggedIn.getEmail(), loggedIn.getRole());
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		SecurityContext context = this.securityContextHolderStrategy.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
		this.securityContextHolderStrategy.setContext(context);
		this.securityContextRepository.saveContext(context, request, response);
		this.csrfTokenRepository.saveToken(this.csrfTokenRepository.generateToken(request), request, response);
	}

}
