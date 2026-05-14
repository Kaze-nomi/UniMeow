package uni.notification.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uni.notification.repository.NotificationRepository;
import uni.notification.repository.ProcessedEventRepository;

@Component
@RequiredArgsConstructor
public class PlatformMetrics implements MeterBinder {

	private final NotificationRepository notificationRepository;
	private final ProcessedEventRepository processedEventRepository;

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("platform_notifications_total", notificationRepository, NotificationRepository::count)
				.description("Total number of stored notifications").register(registry);
		Gauge.builder("platform_notifications_unread_total", notificationRepository,
				NotificationRepository::countByIsReadFalse).description("Total number of unread notifications")
				.register(registry);
		Gauge.builder("platform_notification_processed_events_total", processedEventRepository,
				ProcessedEventRepository::count).description("Total number of processed notification event ids")
				.register(registry);
	}
}
