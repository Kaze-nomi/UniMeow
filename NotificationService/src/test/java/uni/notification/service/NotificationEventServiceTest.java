package uni.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import uni.notification.entity.Notification;
import uni.notification.entity.ProcessedEvent;
import uni.notification.repository.NotificationRepository;
import uni.notification.repository.ProcessedEventRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationEventServiceTest {

	@Mock
	NotificationRepository notificationRepository;

	@Mock
	ProcessedEventRepository processedEventRepository;

	@InjectMocks
	NotificationEventService notificationEventService;

	private static final String EVENT_ID = "event-001";
	private static final String USER_A = "00000000-0000-0000-0000-000000000001";
	private static final String USER_B = "00000000-0000-0000-0000-000000000002";
	private static final String USER_C = "00000000-0000-0000-0000-000000000003";
	private static final String POST_ID = "post-abc";
	private static final String COMMENT_ID = "comment-xyz";

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(notificationEventService, "objectMapper", new ObjectMapper());
	}

	private String event(String eventType, String payload) {
		return """
				{"eventId":"%s","eventType":"%s","occurredAt":"%s","payload":%s}
				""".formatted(EVENT_ID, eventType, Instant.now(), payload);
	}

	private String eventWithId(String eventId, String eventType, String payload) {
		return """
				{"eventId":"%s","eventType":"%s","occurredAt":"%s","payload":%s}
				""".formatted(eventId, eventType, Instant.now(), payload);
	}

	@Test
	void processRaw_skips_when_already_processed() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(true);

		notificationEventService.processRaw(event("POST_CREATED", """
				{"postId":"%s","authorId":"%s","mentionedUserIds":["%s"]}
				""".formatted(POST_ID, USER_A, USER_B)));

		verify(notificationRepository, never()).saveAll(any());
	}

	@Test
	void processRaw_skips_and_logs_when_event_id_blank() {
		String json = """
				{"eventId":"  ","eventType":"POST_CREATED","occurredAt":"%s","payload":{}}
				""".formatted(Instant.now());

		notificationEventService.processRaw(json);

		verify(notificationRepository, never()).saveAll(any());
		verify(processedEventRepository, never()).save(any());
	}

	@Test
	void processRaw_saves_processed_event_after_handling() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("USER_FOLLOWED", """
				{"subscriberId":"%s","targetUserId":"%s"}
				""".formatted(USER_A, USER_B)));

		ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
		verify(processedEventRepository).save(captor.capture());
		assertThat(captor.getValue().getEventId()).isEqualTo(EVENT_ID);
	}

	@Test
	void onPostCreated_creates_mention_notification_for_each_mentioned_user() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("POST_CREATED", """
				{"postId":"%s","authorId":"%s","mentionedUserIds":["%s","%s"]}
				""".formatted(POST_ID, USER_A, USER_B, USER_C)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		List<Notification> saved = captor.getValue();
		assertThat(saved).hasSize(2);
		assertThat(saved).allMatch(n -> n.getType().equals("MENTION_IN_POST"));
		assertThat(saved).allMatch(n -> n.getEntityId().equals(POST_ID));
	}

	@Test
	void onPostCreated_deduplicates_repeated_mentioned_user_ids_in_same_event() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("POST_CREATED", """
				{"postId":"%s","authorId":"%s","mentionedUserIds":["%s","%s"]}
				""".formatted(POST_ID, USER_A, USER_B, USER_B)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		assertThat(captor.getValue()).hasSize(1);
		assertThat(captor.getValue().get(0).getUserId().toString()).isEqualTo(USER_B);
	}

	@Test
	void onPostCreated_skips_mention_if_author_is_mentioned() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("POST_CREATED", """
				{"postId":"%s","authorId":"%s","mentionedUserIds":["%s"]}
				""".formatted(POST_ID, USER_A, USER_A)));

		verify(notificationRepository, never()).saveAll(any());
	}

	@Test
	void onPostCreated_produces_no_notifications_when_no_mentions() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("POST_CREATED", """
				{"postId":"%s","authorId":"%s","mentionedUserIds":[]}
				""".formatted(POST_ID, USER_A)));

		verify(notificationRepository, never()).saveAll(any());
	}

	@Test
	void onPostLiked_creates_like_notification_for_author() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("POST_LIKED", """
				{"postId":"%s","authorId":"%s","actorId":"%s"}
				""".formatted(POST_ID, USER_A, USER_B)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		Notification n = captor.getValue().get(0);
		assertThat(n.getType()).isEqualTo("LIKE_POST");
		assertThat(n.getUserId().toString()).isEqualTo(USER_A);
		assertThat(n.getActorId().toString()).isEqualTo(USER_B);
	}

	@Test
	void onPostLiked_merges_existing_like_notification_in_dedup_window() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);
		Notification existing = Notification.builder().id(UUID.randomUUID()).userId(UUID.fromString(USER_A))
				.actorId(UUID.fromString(USER_B)).type("LIKE_POST").entityId(POST_ID).entityType("POST").isRead(true)
				.createdAt(LocalDateTime.now().minusDays(1)).build();
		when(notificationRepository.findFirstByUserIdAndActorIdAndTypeAndEntityIdAndCreatedAtAfterOrderByCreatedAtDesc(
				eq(UUID.fromString(USER_A)), eq(UUID.fromString(USER_B)), eq("LIKE_POST"), eq(POST_ID), any()))
				.thenReturn(Optional.of(existing));

		notificationEventService.processRaw(event("POST_LIKED", """
				{"postId":"%s","authorId":"%s","actorId":"%s"}
				""".formatted(POST_ID, USER_A, USER_B)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		assertThat(captor.getValue()).containsExactly(existing);
		assertThat(existing.isRead()).isFalse();
		assertThat(existing.getCreatedAt()).isAfter(LocalDateTime.now().minusMinutes(1));
	}

	@Test
	void onPostLiked_skips_self_like() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("POST_LIKED", """
				{"postId":"%s","authorId":"%s","actorId":"%s"}
				""".formatted(POST_ID, USER_A, USER_A)));

		verify(notificationRepository, never()).saveAll(any());
	}

	@Test
	void onCommentCreated_notifies_post_author_when_no_parent() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("COMMENT_CREATED", """
				{"commentId":"%s","postId":"%s","authorId":"%s","postAuthorId":"%s"}
				""".formatted(COMMENT_ID, POST_ID, USER_B, USER_A)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		Notification n = captor.getValue().get(0);
		assertThat(n.getType()).isEqualTo("COMMENT_ON_POST");
		assertThat(n.getUserId().toString()).isEqualTo(USER_A);
	}

	@Test
	void onCommentCreated_merges_duplicate_comment_notification_with_new_event_id() {
		when(processedEventRepository.existsByEventId("event-001")).thenReturn(false);
		when(processedEventRepository.existsByEventId("event-002")).thenReturn(false);

		notificationEventService.processRaw(eventWithId("event-001", "COMMENT_CREATED", """
				{"commentId":"%s","postId":"%s","authorId":"%s","postAuthorId":"%s"}
				""".formatted(COMMENT_ID, POST_ID, USER_B, USER_A)));

		Notification existing = Notification.builder().id(UUID.randomUUID()).userId(UUID.fromString(USER_A))
				.actorId(UUID.fromString(USER_B)).type("COMMENT_ON_POST").entityId(COMMENT_ID).entityType("COMMENT")
				.parentEntityId(POST_ID).isRead(true).createdAt(LocalDateTime.now().minusMinutes(10)).build();
		when(notificationRepository.findFirstByUserIdAndActorIdAndTypeAndEntityIdAndCreatedAtAfterOrderByCreatedAtDesc(
				eq(UUID.fromString(USER_A)), eq(UUID.fromString(USER_B)), eq("COMMENT_ON_POST"), eq(COMMENT_ID), any()))
				.thenReturn(Optional.of(existing));

		notificationEventService.processRaw(eventWithId("event-002", "COMMENT_CREATED", """
				{"commentId":"%s","postId":"%s","authorId":"%s","postAuthorId":"%s"}
				""".formatted(COMMENT_ID, POST_ID, USER_B, USER_A)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository, times(2)).saveAll(captor.capture());
		assertThat(captor.getAllValues().get(1)).containsExactly(existing);
		assertThat(existing.isRead()).isFalse();
	}

	@Test
	void onCommentCreated_notifies_parent_author_on_reply() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("COMMENT_CREATED", """
				{"commentId":"%s","postId":"%s","authorId":"%s","postAuthorId":"%s",
				 "parentCommentId":"parent-1","parentAuthorId":"%s"}
				""".formatted(COMMENT_ID, POST_ID, USER_B, USER_A, USER_C)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		Notification n = captor.getValue().get(0);
		assertThat(n.getType()).isEqualTo("REPLY_TO_COMMENT");
		assertThat(n.getUserId().toString()).isEqualTo(USER_C);
	}

	@Test
	void onCommentCreated_skips_comment_on_own_post() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("COMMENT_CREATED", """
				{"commentId":"%s","postId":"%s","authorId":"%s","postAuthorId":"%s"}
				""".formatted(COMMENT_ID, POST_ID, USER_A, USER_A)));

		verify(notificationRepository, never()).saveAll(any());
	}

	@Test
	void onCommentCreated_adds_mention_and_comment_notification_without_duplicating_already_notified_user() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("COMMENT_CREATED", """
				{"commentId":"%s","postId":"%s","authorId":"%s","postAuthorId":"%s",
				 "mentionedUserIds":["%s","%s"]}
				""".formatted(COMMENT_ID, POST_ID, USER_B, USER_A, USER_A, USER_C)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		List<Notification> saved = captor.getValue();
		assertThat(saved).hasSize(2);
		assertThat(saved)
				.anyMatch(n -> n.getType().equals("COMMENT_ON_POST") && n.getUserId().toString().equals(USER_A));
		assertThat(saved)
				.anyMatch(n -> n.getType().equals("MENTION_IN_COMMENT") && n.getUserId().toString().equals(USER_C));
	}

	@Test
	void onCommentLiked_creates_like_notification_for_comment_author() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("COMMENT_LIKED", """
				{"commentId":"%s","commentAuthorId":"%s","actorId":"%s"}
				""".formatted(COMMENT_ID, USER_A, USER_B)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		Notification n = captor.getValue().get(0);
		assertThat(n.getType()).isEqualTo("LIKE_COMMENT");
		assertThat(n.getUserId().toString()).isEqualTo(USER_A);
		assertThat(n.getActorId().toString()).isEqualTo(USER_B);
	}

	@Test
	void onCommentLiked_skips_self_like() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("COMMENT_LIKED", """
				{"commentId":"%s","commentAuthorId":"%s","actorId":"%s"}
				""".formatted(COMMENT_ID, USER_A, USER_A)));

		verify(notificationRepository, never()).saveAll(any());
	}

	@Test
	void onUserFollowed_creates_follow_notification() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("USER_FOLLOWED", """
				{"subscriberId":"%s","targetUserId":"%s"}
				""".formatted(USER_A, USER_B)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		Notification n = captor.getValue().get(0);
		assertThat(n.getType()).isEqualTo("FOLLOW");
		assertThat(n.getUserId().toString()).isEqualTo(USER_B);
		assertThat(n.getActorId().toString()).isEqualTo(USER_A);
	}

	@Test
	void onUserFollowed_skips_self_follow() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("USER_FOLLOWED", """
				{"subscriberId":"%s","targetUserId":"%s"}
				""".formatted(USER_A, USER_A)));

		verify(notificationRepository, never()).saveAll(any());
	}

	@Test
	void onAdminGranted_creates_admin_granted_notification() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("ADMIN_GRANTED", """
				{"targetUserId":"%s","granterId":"%s"}
				""".formatted(USER_A, USER_B)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		assertThat(captor.getValue().get(0).getType()).isEqualTo("ADMIN_GRANTED");
	}

	@Test
	void onUserBanned_creates_banned_notification() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("USER_BANNED", """
				{"targetUserId":"%s","moderatorId":"%s"}
				""".formatted(USER_A, USER_B)));

		ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationRepository).saveAll(captor.capture());
		assertThat(captor.getValue().get(0).getType()).isEqualTo("BANNED");
	}

	@Test
	void processRaw_ignores_unknown_event_type() {
		when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

		notificationEventService.processRaw(event("UNKNOWN_TYPE", """
				{"foo":"bar"}
				"""));

		verify(notificationRepository, never()).saveAll(any());
		verify(processedEventRepository).save(any());
	}

	@Test
	void processRaw_throws_on_invalid_json() {
		assertThatThrownBy(() -> notificationEventService.processRaw("not json"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Failed to deserialize");
	}
}
