package com.ansh.EventRelay.webhooks;

import com.ansh.EventRelay.events.WebhookEvent;
import com.ansh.EventRelay.events.WebhookEventRepository;
import com.ansh.EventRelay.events.WebhookEventState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookIngestionService {
	private final WebhookEventRepository webhookEventRepository;

	public WebhookIngestionService(WebhookEventRepository webhookEventRepository) {
		this.webhookEventRepository = webhookEventRepository;
	}

	@Transactional
	public IngestionResult ingest(String source, String externalEventId, String rawPayloadJson) {
		WebhookEvent event = new WebhookEvent(source, externalEventId, rawPayloadJson, WebhookEventState.RECEIVED);
		WebhookEvent saved = webhookEventRepository.saveAndFlush(event);
		return IngestionResult.accepted(saved.getId(), false);
	}

	public record IngestionResult(java.util.UUID eventId, boolean duplicate) {
		public static IngestionResult accepted(java.util.UUID id, boolean dup) {
			return new IngestionResult(id, dup);
		}
	}
}
