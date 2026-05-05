package uni.notification.grpc;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.grpc.notification.*;
import uni.notification.entity.Notification;
import uni.notification.service.NotificationPageResult;
import uni.notification.service.NotificationQueryService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationGrpcServerTest {

	@Mock
	NotificationQueryService notificationQueryService;

	@Mock
	StreamObserver<NotificationListResponse> listObserver;

	@Mock
	StreamObserver<MarkAllReadResponse> markObserver;

	@Mock
	StreamObserver<GetUnreadCountResponse> countObserver;

	@InjectMocks
	NotificationGrpcServer server;

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private Notification notification(String type) {
		return Notification.builder().id(UUID.randomUUID()).userId(USER_ID).actorId(ACTOR_ID).type(type)
				.entityId("entity-1").entityType("POST").isRead(false).createdAt(LocalDateTime.now()).build();
	}

	@Test
	void getNotifications_returns_page_with_total() {
		List<Notification> items = List.of(notification("LIKE_POST"), notification("FOLLOW"));
		when(notificationQueryService.getNotifications(USER_ID, 0, 20))
				.thenReturn(new NotificationPageResult(items, 42));

		server.getNotifications(
				GetNotificationsRequest.newBuilder().setUserId(USER_ID.toString()).setPage(0).setSize(20).build(),
				listObserver);

		ArgumentCaptor<NotificationListResponse> captor = ArgumentCaptor.forClass(NotificationListResponse.class);
		verify(listObserver).onNext(captor.capture());
		verify(listObserver).onCompleted();
		NotificationListResponse response = captor.getValue();
		assertThat(response.getTotal()).isEqualTo(42);
		assertThat(response.getNotificationsCount()).isEqualTo(2);
	}

	@Test
	void getNotifications_defaults_to_page_0_size_20_when_not_set() {
		when(notificationQueryService.getNotifications(USER_ID, 0, 0))
				.thenReturn(new NotificationPageResult(List.of(), 0));

		server.getNotifications(GetNotificationsRequest.newBuilder().setUserId(USER_ID.toString()).build(),
				listObserver);

		verify(notificationQueryService).getNotifications(USER_ID, 0, 0);
	}

	@Test
	void getNotifications_maps_notification_fields_to_proto() {
		Notification n = notification("MENTION_IN_POST");
		when(notificationQueryService.getNotifications(USER_ID, 0, 0))
				.thenReturn(new NotificationPageResult(List.of(n), 1));

		server.getNotifications(GetNotificationsRequest.newBuilder().setUserId(USER_ID.toString()).build(),
				listObserver);

		ArgumentCaptor<NotificationListResponse> captor = ArgumentCaptor.forClass(NotificationListResponse.class);
		verify(listObserver).onNext(captor.capture());
		NotificationProto proto = captor.getValue().getNotifications(0);
		assertThat(proto.getType()).isEqualTo("MENTION_IN_POST");
		assertThat(proto.getUserId()).isEqualTo(USER_ID.toString());
		assertThat(proto.getActorId()).isEqualTo(ACTOR_ID.toString());
		assertThat(proto.getIsRead()).isFalse();
	}

	@Test
	void markAllRead_calls_repository_and_returns_success() {
		when(notificationQueryService.markAllRead(USER_ID)).thenReturn(true);

		server.markAllRead(MarkAllReadRequest.newBuilder().setUserId(USER_ID.toString()).build(), markObserver);

		verify(notificationQueryService).markAllRead(USER_ID);
		ArgumentCaptor<MarkAllReadResponse> captor = ArgumentCaptor.forClass(MarkAllReadResponse.class);
		verify(markObserver).onNext(captor.capture());
		assertThat(captor.getValue().getSuccess()).isTrue();
		verify(markObserver).onCompleted();
	}

	@Test
	void getUnreadCount_returns_count_from_repository() {
		when(notificationQueryService.getUnreadCount(USER_ID)).thenReturn(7L);

		server.getUnreadCount(GetUnreadCountRequest.newBuilder().setUserId(USER_ID.toString()).build(), countObserver);

		ArgumentCaptor<GetUnreadCountResponse> captor = ArgumentCaptor.forClass(GetUnreadCountResponse.class);
		verify(countObserver).onNext(captor.capture());
		assertThat(captor.getValue().getCount()).isEqualTo(7L);
		verify(notificationQueryService).getUnreadCount(USER_ID);
		verify(countObserver).onCompleted();
	}

	@Test
	void getUnreadCount_returns_zero_when_no_unread() {
		when(notificationQueryService.getUnreadCount(USER_ID)).thenReturn(0L);

		server.getUnreadCount(GetUnreadCountRequest.newBuilder().setUserId(USER_ID.toString()).build(), countObserver);

		ArgumentCaptor<GetUnreadCountResponse> captor = ArgumentCaptor.forClass(GetUnreadCountResponse.class);
		verify(countObserver).onNext(captor.capture());
		assertThat(captor.getValue().getCount()).isZero();
	}
}
