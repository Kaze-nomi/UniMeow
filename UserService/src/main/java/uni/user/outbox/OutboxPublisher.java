package uni.user.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

	private final OutboxEventRepository outboxEventRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;

	@Scheduled(fixedDelayString = "${app.outbox.publish-delay-ms:1500}")
	@Transactional
	public void publishPendingEvents() {
		if (!outboxEventRepository.tryLockPublisher()) {
			return;
		}
		List<OutboxEvent> events = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAscIdAsc();

		for (OutboxEvent event : events) {
			try {
				kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()).join();
				event.setPublishedAt(LocalDateTime.now());
			} catch (Exception e) {
				log.warn("Failed to publish outbox event {}; will retry", event.getId(), e);
				break;
			}
		}
	}

}
