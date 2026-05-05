package uni.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.notification.entity.Notification;
import uni.notification.repository.NotificationRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationQueryService {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 20;

	private final NotificationRepository notificationRepository;

	@Transactional(readOnly = true)
	public NotificationPageResult getNotifications(UUID userId, int page, int size) {
		Page<Notification> pageResult = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId,
				PageRequest.of(normalizePage(page), normalizeSize(size)));
		return new NotificationPageResult(pageResult.getContent(), pageResult.getTotalElements());
	}

	@Transactional
	public boolean markAllRead(UUID userId) {
		notificationRepository.markAllReadByUserId(userId);
		return true;
	}

	@Transactional(readOnly = true)
	public long getUnreadCount(UUID userId) {
		return notificationRepository.countByUserIdAndIsReadFalse(userId);
	}

	private int normalizePage(int page) {
		return Math.max(DEFAULT_PAGE, page);
	}

	private int normalizeSize(int size) {
		return size > 0 ? size : DEFAULT_SIZE;
	}
}
