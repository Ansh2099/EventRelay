package com.ansh.EventRelay.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class WebhookIngestionController {
	private static final Logger log = LoggerFactory.getLogger(WebhookIngestionController.class);
	private final WebhookSecretsProperties secretsProperties;
	private final SignatureVerifier signatureVerifier;
	private final WebhookIngestionService ingestionService;
	private final ObjectMapper objectMapper;

	public WebhookIngestionController(
			WebhookSecretsProperties secretsProperties,
			SignatureVerifier signatureVerifier,
			WebhookIngestionService ingestionService,
			ObjectMapper objectMapper) {
		this.secretsProperties = secretsProperties;
		this.signatureVerifier = signatureVerifier;
		this.ingestionService = ingestionService;
		this.objectMapper = objectMapper;
	}

	@PostMapping(
			value = "/{source}",
			consumes = MediaType.APPLICATION_JSON_VALUE
	)
	public ResponseEntity<Void> ingest(
			@PathVariable("source") String source,
			@RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
			@RequestBody byte[] body
	) {
		String secret = secretsProperties.getSecretForSource(source);
		if (!signatureVerifier.isValid(body, secret, signature)) {
			throw new UnauthorizedException("Invalid signature");
		}

		String externalEventId = extractExternalEventId(body);
		if (externalEventId == null || externalEventId.isBlank()) {
			throw new BadRequestException("Missing or invalid event ID");
		}

		try {
			ingestionService.ingest(source, externalEventId, new String(body, StandardCharsets.UTF_8));
			return ResponseEntity.status(HttpStatus.OK).build();
		} catch (DataIntegrityViolationException e) {
			log.warn("Duplicate event detected: source={}, externalEventId={}, message={}", 
				source, externalEventId, e.getMessage());
			return ResponseEntity.ok().build();
		} catch (Exception e) {
			// Check if the root cause is DataIntegrityViolationException
			Throwable cause = e.getCause();
			while (cause != null) {
				if (cause instanceof DataIntegrityViolationException) {
					log.warn("Duplicate event detected (wrapped): source={}, externalEventId={}", 
						source, externalEventId);
					return ResponseEntity.ok().build();
				}
				cause = cause.getCause();
			}
			// If not a duplicate key violation, re-throw
			throw e;
		}
	}

	private String extractExternalEventId(byte[] body) {
		try {
			JsonNode root = objectMapper.readTree(body);
			JsonNode idNode = root.get("id");
			if (idNode == null || idNode.isNull()) {
				return null;
			}
			return idNode.asText(null);
		} catch (IOException e) {
			return null;
		}
	}
}
