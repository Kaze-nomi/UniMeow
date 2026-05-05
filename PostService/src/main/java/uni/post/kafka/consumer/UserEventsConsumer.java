package uni.post.kafka.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import uni.post.service.PostService;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventsConsumer {

	private static final String USER_PERMANENT_BANNED = "USER_PERMANENT_BANNED";
	private static final String USER_DELETED = "USER_DELETED";

	private final PostService postService;
	private final ObjectMapper objectMapper;

	@KafkaListener(topics = "${app.kafka.topics.user-events}")
	public void onUserEvent(String raw, Acknowledgment acknowledgment,
			@Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
			@Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key,
			@Header(KafkaHeaders.RECEIVED_PARTITION) int partition, @Header(KafkaHeaders.OFFSET) long offset) {
		JsonNode event;
		try {
			event = objectMapper.readTree(raw);
		} catch (JsonProcessingException e) {
			log.error("Failed to parse user event from topic {} at partition {} offset {}", topic, partition, offset,
					e);
			throw new IllegalArgumentException("Failed to process user event", e);
		}

		String eventType = text(event, "eventType");
		String aggregateId = text(event, "aggregateId");

		if (USER_PERMANENT_BANNED.equals(eventType) || USER_DELETED.equals(eventType)) {
			if (aggregateId == null) {
				log.error("{} payload is incomplete on topic {} at partition {} offset {}", eventType, topic, partition,
						offset);
				throw new IllegalArgumentException(eventType + " payload is incomplete");
			}
			try {
				UUID authorId = UUID.fromString(aggregateId);
				postService.deleteAllContentByAuthor(authorId);
			} catch (IllegalArgumentException e) {
				log.error("Invalid aggregateId {} on topic {} at partition {} offset {}", aggregateId, topic, partition,
						offset, e);
				throw new IllegalArgumentException("Failed to process user event", e);
			}
		} else {
			log.debug("Ignoring user event type {} on topic {}", eventType, topic);
		}

		acknowledgment.acknowledge();
	}

	private static String text(JsonNode node, String field) {
		if (node == null || node.get(field) == null || node.get(field).isNull()) {
			return null;
		}
		String value = node.get(field).asText();
		return value == null || value.isBlank() ? null : value;
	}
}
