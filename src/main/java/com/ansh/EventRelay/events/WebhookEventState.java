package com.ansh.EventRelay.events;

public enum WebhookEventState {
	RECEIVED,
	PROCESSING,
	SUCCESS,
	FAILED,
	DEAD_LETTER
}
