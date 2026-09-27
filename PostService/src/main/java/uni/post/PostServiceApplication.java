package uni.post;

import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
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
	@GrpcGlobalServerInterceptor
	ObservationGrpcServerInterceptor grpcObservationInterceptor(ObservationRegistry registry) {
		return new ObservationGrpcServerInterceptor(registry);
	}

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
