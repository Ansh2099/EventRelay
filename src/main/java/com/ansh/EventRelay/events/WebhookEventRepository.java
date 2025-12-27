package com.ansh.EventRelay.events;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
	Optional<WebhookEvent> findBySourceAndExternalEventId(String source, String externalEventId);

	List<WebhookEvent> findByState(WebhookEventState state);

	List<WebhookEvent> findBySource(String source);

	List<WebhookEvent> findByStateAndSource(WebhookEventState state, String source);

	@Query(
			value = """
				select *
				from webhook_events
				where state in ('RECEIVED', 'FAILED')
				  and (next_retry_at is null or next_retry_at <= now())
				order by created_at asc
				limit :limit
				for update skip locked
				""",
			nativeQuery = true
	)
	List<WebhookEvent> claimNextEligibleEvents(@Param("limit") int limit);
}
