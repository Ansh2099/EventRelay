package com.ansh.EventRelay.worker;

import com.ansh.EventRelay.events.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DefaultWebhookEventHandler implements WebhookEventHandler {
	private static final Logger log = LoggerFactory.getLogger(DefaultWebhookEventHandler.class);

	@Override
	public void handle(WebhookEvent event) {
		log.info("Handling webhook event id={} source={} externalEventId={} state={}",
			event.getId(), event.getSource(), event.getExternalEventId(), event.getState());

		String payload = event.getPayload();
		if (payload != null && payload.contains("\"simulate_failure\":true")) {
			throw new RuntimeException("Simulated handler failure");
		}
	}
}
