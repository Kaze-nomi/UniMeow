package uni.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uni.notification.entity.Notification;
import uni.notification.repository.NotificationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationQueryServiceTest {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Mock
	private NotificationRepository notificationRepository;

	@InjectMocks
	private NotificationQueryService notificationQueryService;

	@Test
	void getNotifications_normalizes_page_and_size() {
		when(notificationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, PageRequest.of(0, 20)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

		notificationQueryService.getNotifications(USER_ID, -5, 0);

		verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(USER_ID, PageRequest.of(0, 20));
	}

	@Test
	void getNotifications_returns_content_and_total() {
		Notification notification = Notification.builder().id(UUID.randomUUID()).userId(USER_ID).actorId(ACTOR_ID)
				.type("FOLLOW").entityId("entity-1").entityType("USER").isRead(false).createdAt(LocalDateTime.now())
				.build();
		when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), eq(PageRequest.of(1, 10))))
				.thenReturn(new PageImpl<>(List.of(notification), PageRequest.of(1, 10), 42));

		NotificationPageResult result = notificationQueryService.getNotifications(USER_ID, 1, 10);

		assertThat(result.notifications()).containsExactly(notification);
		assertThat(result.total()).isEqualTo(42);
	}

	@Test
	void markAllRead_returns_success_after_repository_call() {
		when(notificationRepository.markAllReadByUserId(USER_ID)).thenReturn(3);

		boolean success = notificationQueryService.markAllRead(USER_ID);

		verify(notificationRepository).markAllReadByUserId(USER_ID);
		assertThat(success).isTrue();
	}

	@Test
	void getUnreadCount_returns_repository_count() {
		when(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).thenReturn(7L);

		long count = notificationQueryService.getUnreadCount(USER_ID);

		verify(notificationRepository).countByUserIdAndIsReadFalse(USER_ID);
		assertThat(count).isEqualTo(7L);
	}
}
