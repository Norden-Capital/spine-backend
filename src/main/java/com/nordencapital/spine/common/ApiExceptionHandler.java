package com.nordencapital.spine.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApiException(ApiException ex) {
		return ResponseEntity.status(ex.getStatus()).body(new ApiError(ex.getCode(), ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map((error) -> error.getField() + ": " + error.getDefaultMessage())
			.sorted()
			.findFirst()
			.orElse("Invalid request");
		return ResponseEntity.badRequest().body(new ApiError("validation_error", message));
	}

	@ExceptionHandler(DuplicateException.class)
	ResponseEntity<DuplicateException.Body> handleDuplicate(DuplicateException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new DuplicateException.Body("possible_duplicate", ex.getMessage(), ex.getMatches()));
	}

	/** Upload bigger than spring.servlet.multipart limits. */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
			.body(new ApiError("file_too_large", "A file can be at most 10 MB"));
	}

	/** Not a multipart request, or the "file" part is missing. */
	@ExceptionHandler({ MultipartException.class, MissingServletRequestPartException.class })
	ResponseEntity<ApiError> handleBadUpload(Exception ex) {
		return ResponseEntity.badRequest().body(new ApiError("validation_error", "file: a file is required"));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<ApiError> handleBadRequest(Exception ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new ApiError("bad_request", "The request is not valid"));
	}

}
