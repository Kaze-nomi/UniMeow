package uni.notification.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(String eventId, String eventType, String occurredAt, String aggregateId, JsonNode payload,
		int version) {
}
