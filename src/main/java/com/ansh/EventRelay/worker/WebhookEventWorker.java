package com.ansh.EventRelay.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebhookEventWorker {
	private static final Logger log = LoggerFactory.getLogger(WebhookEventWorker.class);

	private final WebhookEventProcessor processor;
	private final int batchSize;

	public WebhookEventWorker(
			WebhookEventProcessor processor,
			@Value("${eventrelay.worker.batchSize:5}") int batchSize
	) {
		this.processor = processor;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${eventrelay.worker.fixedDelayMs:1000}")
	public void tick() {
		int processed = 0;
		for (int i = 0; i < batchSize; i++) {
			boolean didWork = processor.processNextEligibleEvent();
			if (!didWork) {
				break;
			}
			processed++;
		}
		if (processed > 0) {
			log.info("Worker processed {} event(s)", processed);
		}
	}
}
