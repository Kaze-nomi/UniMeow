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
	@SuppressWarnings("PMD.ExceptionAsFlowControl")
	public void publishPendingEvents() {
		if (!outboxEventRepository.tryLockPublisher() || !outboxEventRepository.existsByPublishedAtIsNull()) {
			return;
		}
		long start = System.nanoTime();
		try {
			List<OutboxEvent> events = kafkaTemplate.executeInTransaction(operations -> {
				if (!outboxEventRepository.tryLockPublisher()) {
					throw new IllegalStateException("Outbox ownership lost during Kafka initialization");
				}
				List<OutboxEvent> pending = outboxEventRepository
						.findTop100ByPublishedAtIsNullOrderByCreatedAtAscIdAsc();
				for (OutboxEvent event : pending) {
					operations.send(event.getTopic(), event.getEventKey(), event.getPayload()).join();
				}
				return pending;
			});
			if (events != null) {
				LocalDateTime publishedAt = LocalDateTime.now();
				events.forEach(event -> event.setPublishedAt(publishedAt));
			}
		} catch (Exception e) {
			log.atWarn().addKeyValue("event", "outbox_publish_failed").addKeyValue("status", "retry")
					.addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000L).setCause(e)
					.log("Failed to commit outbox batch; will retry");
		}
	}
}
