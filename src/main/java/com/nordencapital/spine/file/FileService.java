package com.nordencapital.spine.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.security.SecureRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.config.FilesProperties;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

/**
 * Proof files: upload (checked by real content), serve to allowed users only, and delete
 * old uploads that were never attached.
 * <p>
 * Who may download a file:
 * <ul>
 * <li>attached to a transaction that is not in the trash: every logged-in user;</li>
 * <li>attached to a trashed transaction: OWNER and ADMIN only;</li>
 * <li>not attached yet: only the user who uploaded it.</li>
 * </ul>
 * Anything else is "not found".
 */
@Service
public class FileService {

	public static final long MAX_SIZE = 10L * 1024 * 1024;

	static final Duration UNATTACHED_TTL = Duration.ofHours(24);

	private static final int MAX_NAME_LENGTH = 255;

	private static final Logger logger = LoggerFactory.getLogger(FileService.class);

	private final SecureRandom random = new SecureRandom();

	private final StoredFileRepository files;

	private final Actors actors;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final Path dir;

	public FileService(StoredFileRepository files, Actors actors, JdbcTemplate jdbc, Clock clock,
			FilesProperties properties) {
		this.files = files;
		this.actors = actors;
		this.jdbc = jdbc;
		this.clock = clock;
		this.dir = Path.of(properties.dir()).toAbsolutePath().normalize();
	}

	/**
	 * Saves an upload (not attached yet).
	 * @throws ApiException 413 {@code file_too_large}, 415 {@code file_type_not_allowed},
	 * 400 {@code validation_error} (no file)
	 */
	@Transactional
	public FileResponse upload(AuthUser principal, MultipartFile upload) {
		User user = this.actors.active(principal);
		if (upload == null || upload.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "file: must not be empty");
		}
		if (upload.getSize() > MAX_SIZE) {
			throw tooLarge();
		}
		String key = HexFormat.of().formatHex(randomBytes());
		Path target = this.dir.resolve(key);
		Path temp = null;
		try {
			Files.createDirectories(this.dir);
			temp = Files.createTempFile(this.dir, "upload-", ".tmp");
			long size = copyAtMost(upload, temp);
			String type = FileTypes.detect(head(temp)).orElseThrow(FileService::typeNotAllowed);
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
			temp = null;
			StoredFile file = StoredFile.create(user.getId(), cleanName(upload.getOriginalFilename()), type, size,
					key, this.clock.instant());
			return FileResponse.from(this.files.saveAndFlush(file));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not store the uploaded file", ex);
		}
		catch (RuntimeException ex) {
			deleteQuietly(target);
			throw ex;
		}
		finally {
			if (temp != null) {
				deleteQuietly(temp);
			}
		}
	}

	/**
	 * A file the caller may see, with its path on disk.
	 * @throws ApiException 404 {@code file_not_found}
	 */
	@Transactional(readOnly = true)
	public Download download(AuthUser principal, UUID id) {
		User user = this.actors.active(principal);
		StoredFile file = this.files.findById(id).filter((f) -> canSee(user, f)).orElseThrow(FileService::notFound);
		Path path = this.dir.resolve(file.getStorageKey());
		if (!Files.isRegularFile(path)) {
			logger.error("File {} is in the database but missing on disk", file.getId());
			throw notFound();
		}
		return new Download(file, path);
	}

	/**
	 * Every hour: deletes uploads that were never attached and are older than 24 hours
	 * (database row and file on disk).
	 */
	@Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
	@Transactional
	public int deleteOldUnattached() {
		List<StoredFile> old = this.files
			.findAllByTransactionIdIsNullAndCreatedAtBefore(this.clock.instant().minus(UNATTACHED_TTL));
		for (StoredFile file : old) {
			this.files.delete(file);
			deleteQuietly(this.dir.resolve(file.getStorageKey()));
		}
		if (!old.isEmpty()) {
			logger.info("Deleted {} old unattached upload(s)", old.size());
		}
		return old.size();
	}

	private boolean canSee(User user, StoredFile file) {
		if (file.getTransactionId() == null) {
			return file.getOwnerId().equals(user.getId());
		}
		Optional<Boolean> trashed = this.jdbc
			.queryForList("SELECT deleted_at IS NOT NULL FROM transactions WHERE id = ?", Boolean.class,
					file.getTransactionId())
			.stream()
			.findFirst();
		if (trashed.isEmpty()) {
			return false;
		}
		return !trashed.get() || user.getRole() == Role.OWNER || user.getRole() == Role.ADMIN;
	}

	/** Copies the upload to {@code target}, stopping if it is bigger than the limit. */
	private static long copyAtMost(MultipartFile upload, Path target) throws IOException {
		byte[] buffer = new byte[64 * 1024];
		long total = 0;
		try (InputStream in = upload.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
			int read;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (total > MAX_SIZE) {
					throw tooLarge();
				}
				out.write(buffer, 0, read);
			}
		}
		if (total == 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "file: must not be empty");
		}
		return total;
	}

	private static byte[] head(Path path) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			return in.readNBytes(FileTypes.HEAD_SIZE);
		}
	}

	/** Keeps only the last part of the name (no folders), without control characters. */
	static String cleanName(String original) {
		if (original == null) {
			return "file";
		}
		String name = original.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
		if (name.isEmpty()) {
			return "file";
		}
		if (name.length() > MAX_NAME_LENGTH) {
			int dot = name.lastIndexOf('.');
			String extension = (dot > 0 && name.length() - dot <= 10) ? name.substring(dot) : "";
			name = name.substring(0, MAX_NAME_LENGTH - extension.length()) + extension;
		}
		return name;
	}

	private byte[] randomBytes() {
		byte[] bytes = new byte[16];
		this.random.nextBytes(bytes);
		return bytes;
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ex) {
			logger.warn("Could not delete {}: {}", path.getFileName(), ex.getMessage());
		}
	}

	static ApiException tooLarge() {
		return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "A file can be at most 10 MB");
	}

	private static ApiException typeNotAllowed() {
		return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file_type_not_allowed",
				"Only JPEG, PNG, HEIC, HEIF, WEBP and PDF files are allowed");
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "file_not_found", "File not found");
	}

	/** A file and where it is on disk. */
	public record Download(StoredFile file, Path path) {
	}

}
