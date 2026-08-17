package com.ansh.EventRelay.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {
	private final int maxRetries;
	private final List<Long> delaysSeconds;

	public RetryPolicy(
			@Value("${eventrelay.retry.max:5}") int maxRetries,
			@Value("${eventrelay.retry.delays-seconds:30,120,600,1800,7200}") String delaysConfig
	) {
		this.maxRetries = maxRetries;
		this.delaysSeconds = parseDelays(delaysConfig);
	}

	private List<Long> parseDelays(String config) {
		if (config == null || config.isBlank()) {
			return List.of(30L, 120L, 600L, 1800L, 7200L);
		}
		return Arrays.stream(config.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.map(Long::parseLong)
				.toList();
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public Instant computeNextRetryAt(int nextRetryCount, Instant now) {
		int index = Math.min(Math.max(0, nextRetryCount - 1), delaysSeconds.size() - 1);
		long seconds = delaysSeconds.get(index);
		return now.plus(Duration.ofSeconds(seconds));
	}
}
