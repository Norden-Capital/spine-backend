package com.nordencapital.spine.file;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;

@Tag(name = "Files", description = "Proof files. Upload first, then attach with a transaction (fileIds). "
		+ "Uploads not attached within 24 hours are deleted. Files are never public.")
@RestController
public class FileController {

	private final FileService service;

	public FileController(FileService service) {
		this.service = service;
	}

	@Operation(summary = "Upload a proof file",
			description = "multipart/form-data, field \"file\". JPEG, PNG, HEIC, HEIF, WEBP or PDF (checked by content), "
					+ "max 10 MB.")
	@ApiResponse(responseCode = "201", description = "Uploaded (not attached yet)")
	@ApiResponse(responseCode = "413", description = "file_too_large",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "415", description = "file_type_not_allowed",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping(path = "/api/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<FileResponse> upload(@AuthenticationPrincipal AuthUser actor,
			@RequestParam("file") MultipartFile file) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.upload(actor, file));
	}

	@Operation(summary = "Get a file",
			description = "Shown inline. Only for a file on a transaction you can see, or your own new upload.")
	@ApiResponse(responseCode = "200", description = "The file")
	@ApiResponse(responseCode = "404", description = "file_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/api/files/{id}")
	public ResponseEntity<Resource> download(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		FileService.Download download = this.service.download(actor, id, request.getSession(false));
		StoredFile file = download.file();
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.getContentType()))
			.contentLength(file.getSize())
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename(file.getOriginalName(), StandardCharsets.UTF_8).build().toString())
			.cacheControl(CacheControl.noStore().cachePrivate())
			.body(new FileSystemResource(download.path()));
	}

}
