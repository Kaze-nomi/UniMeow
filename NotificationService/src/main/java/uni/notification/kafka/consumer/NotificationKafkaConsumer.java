package uni.notification.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;
import uni.notification.kafka.producer.NotificationDlqProducer;
import uni.notification.service.NotificationEventService;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationKafkaConsumer {

	private final NotificationEventService notificationEventService;
	private final NotificationDlqProducer dlqProducer;

	@Bean
	DefaultErrorHandler kafkaErrorHandler() {
		DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
		handler.setClassifications(Map.of(), true);
		return handler;
	}

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
		long start = System.nanoTime();
		try {
			notificationEventService.processRaw(raw);
		} catch (IllegalArgumentException e) {
			boolean published = dlqProducer.publishConsumerFailure(topic, key, raw, partition, offset, e);
			if (published) {
				acknowledgment.acknowledge();
				log.atWarn().addKeyValue("event", "kafka_consumer_dlq").addKeyValue("topic", topic)
						.addKeyValue("key", key).addKeyValue("partition", partition).addKeyValue("offset", offset)
						.addKeyValue("status", "dlq").addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000L)
						.setCause(e).log("Event moved to DLQ");
				return;
			}
			log.atError().addKeyValue("event", "kafka_consumer_failed").addKeyValue("topic", topic)
					.addKeyValue("key", key).addKeyValue("partition", partition).addKeyValue("offset", offset)
					.addKeyValue("status", "retry").addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000L)
					.setCause(e).log("Event processing failed and DLQ publish failed");
			throw e;
		} catch (Exception e) {
			log.atError().addKeyValue("event", "kafka_consumer_failed").addKeyValue("topic", topic)
					.addKeyValue("key", key).addKeyValue("partition", partition).addKeyValue("offset", offset)
					.addKeyValue("status", "retry").addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000L)
					.setCause(e).log("Event processing failed; will retry");
			throw e;
		}
		acknowledgment.acknowledge();
	}
}
