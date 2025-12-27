package com.ansh.EventRelay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ansh.EventRelay.events.WebhookEvent;
import com.ansh.EventRelay.events.WebhookEventRepository;
import com.ansh.EventRelay.events.WebhookEventState;
import com.ansh.EventRelay.worker.WebhookEventHandler;
import com.ansh.EventRelay.worker.WebhookEventProcessor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
		"spring.task.scheduling.enabled=false",
		"spring.main.allow-bean-definition-overriding=true",
		"eventrelay.webhook.secrets.test=test-secret"
		}
)
class WebhookFlowIntegrationTests {
	@Container
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
			.withDatabaseName("event_relay")
			.withUsername("postgres")
			.withPassword("postgres");

	@DynamicPropertySource
	static void registerDataSource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@Autowired
	TestRestTemplate restTemplate;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	WebhookEventRepository repository;

	@Autowired
	WebhookEventProcessor processor;

	@Autowired
	CountingWebhookEventHandler handler;

	@BeforeEach
	void reset() {
		jdbcTemplate.execute("truncate table webhook_events");
		handler.reset();
	}

	@Test
	void idempotency_sameWebhookTwice_oneRow_processedOnce() throws Exception {
		String payload = "{\"id\":\"evt_idempotent\"}";
		sendSignedWebhook("test", payload);

		boolean processedFirst = processor.processNextEligibleEvent();
		assertTrue(processedFirst);
		assertEquals(1, handler.getHandledCount());

		sendSignedWebhook("test", payload);

		assertEquals(1L, repository.count());
		WebhookEvent saved = repository.findBySourceAndExternalEventId("test", "evt_idempotent").orElseThrow();
		assertEquals(WebhookEventState.SUCCESS, saved.getState());

		boolean processedSecond = processor.processNextEligibleEvent();
		assertEquals(false, processedSecond);
		assertEquals(1, handler.getHandledCount());
	}

	@Test
	void retryFlow_failedThenEventuallySuccess() throws Exception {
		String payload = "{\"id\":\"evt_retry\",\"simulate_failure\":true}";
		handler.failNextTimesForEvent("evt_retry", 1);
		sendSignedWebhook("test", payload);

		boolean processed = processor.processNextEligibleEvent();
		assertTrue(processed);

		WebhookEvent failed = repository.findBySourceAndExternalEventId("test", "evt_retry").orElseThrow();
		assertEquals(WebhookEventState.FAILED, failed.getState());
		assertEquals(1, failed.getRetryCount());
		assertNotNull(failed.getNextRetryAt());
		forceEligible(failed.getId());

		boolean processedAgain = processor.processNextEligibleEvent();
		assertTrue(processedAgain);

		WebhookEvent success = repository.findBySourceAndExternalEventId("test", "evt_retry").orElseThrow();
		assertEquals(WebhookEventState.SUCCESS, success.getState());
		assertEquals(1, success.getRetryCount());
		assertNull(success.getNextRetryAt());
	}

	@Test
	void deadLetter_failureBeyondMaxRetries() throws Exception {
		String payload = "{\"id\":\"evt_dead\",\"simulate_failure\":true}";
		handler.failNextTimesForEvent("evt_dead", 10);
		sendSignedWebhook("test", payload);

		UUID id = repository.findBySourceAndExternalEventId("test", "evt_dead").orElseThrow().getId();

		for (int attempt = 0; attempt < 10; attempt++) {
			boolean didWork = processor.processNextEligibleEvent();
			assertTrue(didWork);

			WebhookEvent event = repository.findById(id).orElseThrow();
			if (event.getState() == WebhookEventState.DEAD_LETTER) {
				assertNull(event.getNextRetryAt());
				return;
			}

			forceEligible(id);
		}

		WebhookEvent finalEvent = repository.findById(id).orElseThrow();
		assertEquals(WebhookEventState.DEAD_LETTER, finalEvent.getState());
		assertNull(finalEvent.getNextRetryAt());
	}

	private void forceEligible(UUID eventId) {
		jdbcTemplate.update(
				"update webhook_events set next_retry_at = now() - interval '1 second' where id = ?",
				eventId
		);
	}

	private void sendSignedWebhook(String source, String payloadJson) throws Exception {
		byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
		String signature = sign(body, "test-secret");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("X-Webhook-Signature", signature);

		HttpEntity<String> entity = new HttpEntity<>(payloadJson, headers);
		ResponseEntity<Void> response = restTemplate.exchange(
				"http://localhost:" + port + "/webhooks/" + source,
				HttpMethod.POST,
				entity,
				Void.class
		);
		assertEquals(HttpStatus.OK, response.getStatusCode());
	}

	private String sign(byte[] body, String secret) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getEncoder().encodeToString(mac.doFinal(body));
	}

	@TestConfiguration
	static class HandlerTestConfig {
		@Bean(name = "defaultWebhookEventHandler")
		@Primary
		CountingWebhookEventHandler defaultWebhookEventHandler() {
			return new CountingWebhookEventHandler();
		}
	}

	static class CountingWebhookEventHandler implements WebhookEventHandler {
		private final AtomicInteger handledCount = new AtomicInteger(0);
		private final ConcurrentMap<String, AtomicInteger> remainingFailuresByEventId = new ConcurrentHashMap<>();

		@Override
		public void handle(WebhookEvent event) {
			handledCount.incrementAndGet();
			String eventId = event.getExternalEventId();
			AtomicInteger remaining = remainingFailuresByEventId.get(eventId);
			if (remaining != null && remaining.getAndDecrement() > 0) {
				throw new RuntimeException("Simulated handler failure for " + eventId);
			}
		}

		public int getHandledCount() {
			return handledCount.get();
		}

		public void failNextTimesForEvent(String externalEventId, int times) {
			remainingFailuresByEventId.put(externalEventId, new AtomicInteger(times));
		}

		public void reset() {
			handledCount.set(0);
			remainingFailuresByEventId.clear();
		}
	}
}
