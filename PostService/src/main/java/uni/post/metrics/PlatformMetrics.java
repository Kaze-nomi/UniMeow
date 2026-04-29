package uni.post.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uni.post.repository.PostRepository;

@Component
@RequiredArgsConstructor
public class PlatformMetrics implements MeterBinder {

	private final PostRepository postRepository;

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("platform_posts_total", postRepository, PostRepository::count)
				.description("Total number of posts on the platform").register(registry);
	}
}
