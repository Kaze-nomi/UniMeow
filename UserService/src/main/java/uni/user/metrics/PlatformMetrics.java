package uni.user.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uni.user.repository.UserRepository;

@Component
@RequiredArgsConstructor
public class PlatformMetrics implements MeterBinder {

	private final UserRepository userRepository;

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("platform_users_total", userRepository, UserRepository::count)
				.description("Total number of registered users on the platform").register(registry);
	}
}
