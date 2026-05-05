package uni.notification.kafka.producer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDlqProducer {

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;

	@Value("${app.kafka.topics.notification-events-dlq:notification-events.dlq}")
	private String dlqTopic;

	public boolean publishConsumerFailure(String sourceTopic, String key, String rawPayload, int partition, long offset,
			Exception error) {
		try {
			JsonNode payloadNode = tryReadJson(rawPayload);
			String message = objectMapper.writeValueAsString(Map.of("failedAt", Instant.now().toString(), "sourceTopic",
					sourceTopic, "sourcePartition", partition, "sourceOffset", offset, "messageKey",
					key == null ? "" : key, "errorClass", error.getClass().getName(), "errorMessage",
					error.getMessage() == null ? "" : error.getMessage(), "payload",
					payloadNode == null ? rawPayload : payloadNode));
			kafkaTemplate.send(dlqTopic, key == null ? sourceTopic : key, message).join();
			return true;
		} catch (Exception e) {
			log.error("Failed to publish event to DLQ topic {}", dlqTopic, e);
			return false;
		}
	}

	private JsonNode tryReadJson(String raw) {
		try {
			return objectMapper.readTree(raw);
		} catch (Exception ignored) {
			return null;
		}
	}
}
