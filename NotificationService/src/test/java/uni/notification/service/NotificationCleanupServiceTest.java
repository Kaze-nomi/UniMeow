package uni.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import uni.notification.repository.NotificationRepository;
import uni.notification.repository.ProcessedEventRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationCleanupServiceTest {

	@Mock
	NotificationRepository notificationRepository;

	@Mock
	ProcessedEventRepository processedEventRepository;

	@InjectMocks
	NotificationCleanupService cleanupService;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(cleanupService, "ttlDays", 90);
	}

	@Test
	void cleanup_deletes_notifications_and_events_older_than_ttl() {
		when(notificationRepository.deleteOlderThan(any())).thenReturn(15);
		when(processedEventRepository.deleteOlderThan(any())).thenReturn(8);

		cleanupService.cleanup();

		verify(notificationRepository).deleteOlderThan(any(LocalDateTime.class));
		verify(processedEventRepository).deleteOlderThan(any(LocalDateTime.class));
	}

	@Test
	void cleanup_uses_cutoff_approximately_ttl_days_ago() {
		when(notificationRepository.deleteOlderThan(any())).thenReturn(0);
		when(processedEventRepository.deleteOlderThan(any())).thenReturn(0);

		LocalDateTime before = LocalDateTime.now().minusDays(90).minusSeconds(1);
		cleanupService.cleanup();
		LocalDateTime after = LocalDateTime.now().minusDays(90).plusSeconds(1);

		ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(notificationRepository).deleteOlderThan(captor.capture());
		LocalDateTime cutoff = captor.getValue();
		assertThat(cutoff).isAfter(before).isBefore(after);
	}

	@Test
	void cleanup_still_runs_when_nothing_to_delete() {
		when(notificationRepository.deleteOlderThan(any())).thenReturn(0);
		when(processedEventRepository.deleteOlderThan(any())).thenReturn(0);

		assertThatNoException().isThrownBy(() -> cleanupService.cleanup());
	}

	@Test
	void cleanup_respects_custom_ttl_days() {
		ReflectionTestUtils.setField(cleanupService, "ttlDays", 30);
		when(notificationRepository.deleteOlderThan(any())).thenReturn(0);
		when(processedEventRepository.deleteOlderThan(any())).thenReturn(0);

		LocalDateTime before = LocalDateTime.now().minusDays(30).minusSeconds(1);
		cleanupService.cleanup();
		LocalDateTime after = LocalDateTime.now().minusDays(30).plusSeconds(1);

		ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(notificationRepository).deleteOlderThan(captor.capture());
		assertThat(captor.getValue()).isAfter(before).isBefore(after);
	}
}
