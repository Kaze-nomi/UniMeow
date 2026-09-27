package uni.post;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultTransactionIdSuffixStrategy;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class PostServiceApplication {
	@Bean
	static DefaultKafkaProducerFactoryCustomizer outboxProducerFactoryCustomizer() {
		return factory -> {
			factory.setTransactionIdPrefix("unimeow-post-outbox-");
			factory.setTransactionIdSuffixStrategy(new DefaultTransactionIdSuffixStrategy(1));
		};
	}

	public static void main(String[] args) {
		SpringApplication.run(PostServiceApplication.class, args);
	}
}
