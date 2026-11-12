package com.nordencapital.spine.logo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.util.DigestUtils;

import com.nordencapital.spine.auth.AuthUser;

@RestController
@Tag(name = "Logos", description = "Website logos of transactions, fetched and cached by the server.")
public class LogoController {

	private final LogoService service;

	public LogoController(LogoService service) {
		this.service = service;
	}

	@Operation(summary = "Logo of a transaction website",
			description = "Use a logoUrl. Only for domains on company transactions/categories or on your own wallet "
					+ "transactions/categories. The first request may take a few seconds (the server fetches it). "
					+ "Cache-Control no-cache + ETag: the browser keeps the image but asks every time (304 if same).")
	@ApiResponse(responseCode = "200", description = "PNG, JPEG, WebP or ICO image")
	@ApiResponse(responseCode = "404", description = "logo_not_found (no logo: show the category icon)")
	@GetMapping("/api/logos/{domain:.+}")
	public ResponseEntity<byte[]> logo(@AuthenticationPrincipal AuthUser actor, @PathVariable String domain,
			WebRequest request) {
		// The access check runs first, every time: the browser must ask again (no-cache) so a
		// logo cached for one user is never shown to another user of the same browser.
		LogoService.Cached logo = this.service.get(actor, domain);
		String etag = "\"" + DigestUtils.md5DigestAsHex(logo.data()) + "\"";
		CacheControl cache = CacheControl.noCache().cachePrivate();
		if (request.checkNotModified(etag)) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
		}
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(logo.contentType()))
			.cacheControl(cache)
			.eTag(etag)
			.header("Vary", "Cookie")
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", "default-src 'none'; sandbox")
			.header("Content-Disposition", "inline")
			.body(logo.data());
	}

}
