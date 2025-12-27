package com.ansh.EventRelay.events;

import com.ansh.EventRelay.webhooks.BadRequestException;
import com.ansh.EventRelay.webhooks.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
public class EventsController {
	private final WebhookEventRepository webhookEventRepository;

	public EventsController(WebhookEventRepository webhookEventRepository) {
		this.webhookEventRepository = webhookEventRepository;
	}

	@GetMapping("/{eventId}")
	public WebhookEventDto getById(@PathVariable("eventId") String eventId) {
		UUID id;
		try {
			id = UUID.fromString(eventId);
		} catch (IllegalArgumentException e) {
			throw new BadRequestException("Invalid event ID");
		}

		WebhookEvent event = webhookEventRepository.findById(id)
				.orElseThrow(() -> new NotFoundException("Event not found"));
		return WebhookEventDto.from(event);
	}

	@GetMapping
	public List<WebhookEventDto> query(
			@RequestParam(value = "state", required = false) String state,
			@RequestParam(value = "source", required = false) String source
	) {
		List<WebhookEvent> events;
		if (state != null && !state.isBlank()) {
			WebhookEventState parsed;
			try {
				parsed = WebhookEventState.valueOf(state);
			} catch (IllegalArgumentException e) {
				throw new BadRequestException("Invalid state");
			}

			if (source != null && !source.isBlank()) {
				events = webhookEventRepository.findByStateAndSource(parsed, source);
			} else {
				events = webhookEventRepository.findByState(parsed);
			}
		} else if (source != null && !source.isBlank()) {
			events = webhookEventRepository.findBySource(source);
		} else {
			events = webhookEventRepository.findAll();
		}

		return events.stream().map(WebhookEventDto::from).toList();
	}
}
