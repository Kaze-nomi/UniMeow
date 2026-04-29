package uni.user.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {

	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	@Value("${app.kafka.topics.user-events:user-events}")
	private String userEventsTopic;

	public void enqueueUserEvent(String eventType, String eventKey, String aggregateId, Map<String, Object> payload) {
		try {
			String eventId = UUID.randomUUID().toString();
			String occurredAt = Instant.now().toString();

			String json = objectMapper.writeValueAsString(Map.of("eventId", eventId, "eventType", eventType,
					"occurredAt", occurredAt, "aggregateId", aggregateId, "version", 1, "payload", payload));

			outboxEventRepository.save(OutboxEvent.builder().id(UUID.randomUUID()).topic(userEventsTopic)
					.eventKey(eventKey).payload(json).createdAt(LocalDateTime.now()).build());
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize outbox payload", e);
		}
	}
}
