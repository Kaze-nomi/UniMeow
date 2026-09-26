package uni.notification.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import uni.notification.kafka.producer.NotificationDlqProducer;
import uni.notification.service.NotificationEventService;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationKafkaConsumer {

	private final NotificationEventService notificationEventService;
	private final NotificationDlqProducer dlqProducer;

	@KafkaListener(topics = "${app.kafka.topics.post-events}")
	public void onPostEvent(String raw, Acknowledgment acknowledgment,
			@Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
			@Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key,
			@Header(KafkaHeaders.RECEIVED_PARTITION) int partition, @Header(KafkaHeaders.OFFSET) long offset) {
		consume(raw, acknowledgment, topic, key, partition, offset);
	}

	@KafkaListener(topics = "${app.kafka.topics.user-events}")
	public void onUserEvent(String raw, Acknowledgment acknowledgment,
			@Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
			@Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key,
			@Header(KafkaHeaders.RECEIVED_PARTITION) int partition, @Header(KafkaHeaders.OFFSET) long offset) {
		consume(raw, acknowledgment, topic, key, partition, offset);
	}

	private void consume(String raw, Acknowledgment acknowledgment, String topic, String key, int partition,
			long offset) {
		try {
			notificationEventService.processRaw(raw);
			acknowledgment.acknowledge();
		} catch (Exception e) {
			boolean published = dlqProducer.publishConsumerFailure(topic, key, raw, partition, offset, e);
			if (published) {
				acknowledgment.acknowledge();
				log.warn("Event moved to DLQ: topic={}, partition={}, offset={}", topic, partition, offset);
				return;
			}
			log.error("Event processing failed and DLQ publish failed: topic={}, partition={}, offset={}", topic,
					partition, offset, e);
			throw e;
		}
	}
}
