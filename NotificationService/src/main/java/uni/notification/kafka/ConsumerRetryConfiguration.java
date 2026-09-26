package uni.notification.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import java.util.Map;

@Configuration
public class ConsumerRetryConfiguration {
	@Bean
	CommonErrorHandler consumerErrorHandler() {
		// Only the listener's successful effect/DLQ branch acknowledges a record.
		// Infrastructure failure must not fall through Kafka's default recoverer.
		DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
		handler.setClassifications(Map.of(), true);
		handler.setAckAfterHandle(false);
		return handler;
	}
}
