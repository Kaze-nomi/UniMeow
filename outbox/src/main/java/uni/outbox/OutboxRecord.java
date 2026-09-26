package uni.outbox;

import java.util.UUID;

public record OutboxRecord(UUID id, String topic, String key, String payload) {
}
