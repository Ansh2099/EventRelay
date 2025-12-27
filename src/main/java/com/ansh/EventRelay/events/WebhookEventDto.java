package com.ansh.EventRelay.events;

import java.time.Instant;
import java.util.UUID;

public record WebhookEventDto(
		UUID id,
		String source,
		String externalEventId,
		WebhookEventState state,
		int retryCount,
		Instant nextRetryAt,
		String failureReason,
		Instant createdAt,
		Instant updatedAt
) {
	public static WebhookEventDto from(WebhookEvent event) {
		return new WebhookEventDto(
			event.getId(),
			event.getSource(),
			event.getExternalEventId(),
			event.getState(),
			event.getRetryCount(),
			event.getNextRetryAt(),
			event.getFailureReason(),
			event.getCreatedAt(),
			event.getUpdatedAt()
		);
	}
}
