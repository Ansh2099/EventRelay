package com.ansh.EventRelay.worker;

import com.ansh.EventRelay.events.WebhookEvent;
import com.ansh.EventRelay.events.WebhookEventRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WebhookEventProcessor {
	private static final Logger log = LoggerFactory.getLogger(WebhookEventProcessor.class);

	private final WebhookEventRepository repository;
	private final WebhookEventHandler handler;
	private final RetryPolicy retryPolicy;

	public WebhookEventProcessor(
			WebhookEventRepository repository,
			WebhookEventHandler handler,
			RetryPolicy retryPolicy
	) {
		this.repository = repository;
		this.handler = handler;
		this.retryPolicy = retryPolicy;
	}

	@Transactional
	public boolean processNextEligibleEvent() {
		List<WebhookEvent> claimed = repository.claimNextEligibleEvents(1);
		if (claimed.isEmpty()) {
			return false;
		}

		WebhookEvent event = claimed.getFirst();
		Instant now = Instant.now();
		try {
			event.markProcessing(now);
			repository.save(event);
			log.info("event_transition event_id={} state={} retry_count={}",
				event.getId(), event.getState(), event.getRetryCount());

			handler.handle(event);

			event.markSuccess(Instant.now());
			repository.save(event);
			log.info("event_transition event_id={} state={} retry_count={}",
				event.getId(), event.getState(), event.getRetryCount());
		} catch (Exception ex) {
			handleFailure(event, ex);
		}
		return true;
	}

	private void handleFailure(WebhookEvent event, Exception ex) {
		Instant now = Instant.now();
		int nextRetryCount = event.getRetryCount() + 1;
		String reason = ex.getClass().getSimpleName();

		if (nextRetryCount > retryPolicy.getMaxRetries()) {
			log.warn("event_transition event_id={} state=DEAD_LETTER retry_count={} reason={}",
				event.getId(), nextRetryCount, reason);
			event.markDeadLetter(reason, nextRetryCount, now);
			repository.save(event);
			return;
		}

		log.warn("event_transition event_id={} state=FAILED retry_count={} reason={}",
			event.getId(), nextRetryCount, reason);
		event.markFailed(reason, nextRetryCount, retryPolicy.computeNextRetryAt(nextRetryCount, now), now);
		repository.save(event);
	}
}
