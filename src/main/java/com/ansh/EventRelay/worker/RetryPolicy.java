package com.ansh.EventRelay.worker;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {
	private final int maxRetries;

	public RetryPolicy(@Value("${eventrelay.retry.max:5}") int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public Instant computeNextRetryAt(int nextRetryCount, Instant now) {
		Duration delay = switch (nextRetryCount) {
			case 1 -> Duration.ofSeconds(30);
			case 2 -> Duration.ofMinutes(2);
			case 3 -> Duration.ofMinutes(10);
			case 4 -> Duration.ofMinutes(30);
			default -> Duration.ofHours(2);
		};
		return now.plus(delay);
	}
}
