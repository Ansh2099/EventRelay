package com.ansh.EventRelay.webhooks;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
	@ExceptionHandler(UnauthorizedException.class)
	public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(Map.of("error", "unauthorized"));
	}

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<Map<String, String>> badRequest(BadRequestException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", "bad_request"));
	}

	@ExceptionHandler({NotFoundException.class})
	public ResponseEntity<Map<String, String>> notFound(NotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(Map.of("error", "not_found"));
	}

	@ExceptionHandler({IllegalArgumentException.class})
	public ResponseEntity<Map<String, String>> illegalArgument(IllegalArgumentException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", "bad_request"));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Void> dataIntegrityViolation(DataIntegrityViolationException ex) {
		String message = ex.getMessage();
		if (message != null && message.contains("uk_webhook_events_source_external_event_id")) {
			return ResponseEntity.status(HttpStatus.OK).build();
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> internal(Exception ex) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", "internal_error"));
	}
}
