package uni.feed;

import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class FeedServiceApplication {
	@GrpcGlobalServerInterceptor
	ObservationGrpcServerInterceptor grpcObservationInterceptor(ObservationRegistry registry) {
		return new ObservationGrpcServerInterceptor(registry);
	}

	public static void main(String[] args) {
		SpringApplication.run(FeedServiceApplication.class, args);
	}
}
