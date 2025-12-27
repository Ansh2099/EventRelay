package com.ansh.EventRelay.worker;

import com.ansh.EventRelay.events.WebhookEvent;

public interface WebhookEventHandler {
	void handle(WebhookEvent event);
}
