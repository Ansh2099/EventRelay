package com.ansh.EventRelay.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "webhook_events",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_webhook_events_source_external_event_id",
						columnNames = {"source", "external_event_id"}
				)
		}
)
public class WebhookEvent {
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "source", nullable = false)
	private String source;

	@Column(name = "external_event_id", nullable = false)
	private String externalEventId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, columnDefinition = "jsonb")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "state", nullable = false)
	private WebhookEventState state;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "next_retry_at")
	private Instant nextRetryAt;

	@Column(name = "failure_reason")
	private String failureReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected WebhookEvent() {
	}

	public WebhookEvent(String source,
					  String externalEventId,
					  String payload,
					  WebhookEventState state) {
		this.source = source;
		this.externalEventId = externalEventId;
		this.payload = payload;
		this.state = state;
		this.retryCount = 0;
		this.nextRetryAt = null;
		this.failureReason = null;
	}

	@PrePersist
	public void prePersist() {
		Instant now = Instant.now();
		if (this.id == null) {
			this.id = UUID.randomUUID();
		}
		if (this.createdAt == null) {
			this.createdAt = now;
		}
		this.updatedAt = now;
	}

	@PreUpdate
	public void preUpdate() {
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getSource() {
		return source;
	}

	public String getExternalEventId() {
		return externalEventId;
	}

	public String getPayload() {
		return payload;
	}

	public WebhookEventState getState() {
		return state;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public Instant getNextRetryAt() {
		return nextRetryAt;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void markProcessing(Instant now) {
		this.state = WebhookEventState.PROCESSING;
		this.updatedAt = now;
	}

	public void markSuccess(Instant now) {
		this.state = WebhookEventState.SUCCESS;
		this.failureReason = null;
		this.nextRetryAt = null;
		this.updatedAt = now;
	}

	public void markFailed(String failureReason, int retryCount, Instant nextRetryAt, Instant now) {
		this.state = WebhookEventState.FAILED;
		this.failureReason = failureReason;
		this.retryCount = retryCount;
		this.nextRetryAt = nextRetryAt;
		this.updatedAt = now;
	}

	public void markDeadLetter(String failureReason, int retryCount, Instant now) {
		this.state = WebhookEventState.DEAD_LETTER;
		this.failureReason = failureReason;
		this.retryCount = retryCount;
		this.nextRetryAt = null;
		this.updatedAt = now;
	}
}
