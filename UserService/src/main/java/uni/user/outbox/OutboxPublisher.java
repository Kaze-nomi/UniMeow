package uni.user.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

	private final OutboxEventRepository outboxEventRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;

	@Value("${app.kafka.topics.user-events-dlq:user-events.dlq}")
	private String userEventsDlqTopic;

	@Scheduled(fixedDelayString = "${app.outbox.publish-delay-ms:1500}")
	@Transactional
	public void publishPendingEvents() {
		List<OutboxEvent> events = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

		for (OutboxEvent event : events) {
			try {
				kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()).join();
				event.setPublishedAt(LocalDateTime.now());
			} catch (Exception e) {
				boolean dlqPublished = publishToDlq(event, e);
				if (dlqPublished) {
					event.setPublishedAt(LocalDateTime.now());
					log.warn("Outbox event {} moved to producer DLQ", event.getId());
					continue;
				}

				log.warn("Failed to publish outbox event {} and failed to publish to DLQ", event.getId(), e);
				break;
			}
		}
	}

	private boolean publishToDlq(OutboxEvent event, Exception sourceError) {
		try {
			JsonNode originalPayload = tryReadJson(event.getPayload());

			String dlqPayload = objectMapper.writeValueAsString(Map.of("failedAt", Instant.now().toString(),
					"sourceTopic", event.getTopic(), "eventKey", event.getEventKey(), "outboxId",
					event.getId().toString(), "errorClass", sourceError.getClass().getName(), "errorMessage",
					sourceError.getMessage() == null ? "" : sourceError.getMessage(), "payload",
					originalPayload == null ? event.getPayload() : originalPayload));

			kafkaTemplate.send(userEventsDlqTopic, event.getEventKey(), dlqPayload).join();
			return true;
		} catch (Exception e) {
			log.error("Failed to publish producer DLQ message for outbox event {}", event.getId(), e);
			return false;
		}
	}

	private JsonNode tryReadJson(String rawJson) {
		try {
			return objectMapper.readTree(rawJson);
		} catch (Exception ignored) {
			return null;
		}
	}
}
