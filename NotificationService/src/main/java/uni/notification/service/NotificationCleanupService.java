package uni.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.notification.repository.NotificationRepository;
import uni.notification.repository.ProcessedEventRepository;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCleanupService {

	private final NotificationRepository notificationRepository;
	private final ProcessedEventRepository processedEventRepository;

	@Value("${app.notification.ttl-days:90}")
	private int ttlDays;

	@Scheduled(cron = "${app.notification.cleanup-cron:0 0 3 * * *}")
	@Transactional
	public void cleanup() {
		LocalDateTime cutoff = LocalDateTime.now().minusDays(ttlDays);
		int deletedNotifications = notificationRepository.deleteOlderThan(cutoff);
		int deletedEvents = processedEventRepository.deleteOlderThan(cutoff);
		log.info("Cleanup: deleted {} notifications and {} processed events older than {} days", deletedNotifications,
				deletedEvents, ttlDays);
	}
}
