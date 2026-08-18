package com.ansh.EventRelay.worker;

import com.ansh.EventRelay.events.WebhookEvent;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DefaultWebhookEventHandler implements WebhookEventHandler {

	@Override
	public void handle(WebhookEvent event) {
		log.info("Handling webhook event id={} source={} externalEventId={} state={}",
			event.getId(), event.getSource(), event.getExternalEventId(), event.getState());

		String payload = event.getPayload();
		if (payload != null && (payload.contains("\"simulate_failure\":true") || payload.contains("\"simulate_failure\": true") || payload.contains("simulate_failure"))) {
			throw new RuntimeException("Simulated handler failure");
		}
	}
}
