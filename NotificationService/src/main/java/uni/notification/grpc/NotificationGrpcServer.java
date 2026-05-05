package uni.notification.grpc;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import uni.grpc.notification.*;
import uni.notification.entity.Notification;
import uni.notification.service.NotificationPageResult;
import uni.notification.service.NotificationQueryService;

import java.util.UUID;

@GrpcService
@RequiredArgsConstructor
public class NotificationGrpcServer extends NotificationServiceGrpc.NotificationServiceImplBase {

	private final NotificationQueryService notificationQueryService;

	@Override
	public void getNotifications(GetNotificationsRequest request,
			StreamObserver<NotificationListResponse> responseObserver) {
		UUID userId = UUID.fromString(request.getUserId());
		NotificationPageResult pageResult = notificationQueryService.getNotifications(userId, request.getPage(),
				request.getSize());

		NotificationListResponse.Builder response = NotificationListResponse.newBuilder().setTotal(pageResult.total());

		for (Notification n : pageResult.notifications()) {
			response.addNotifications(toProto(n));
		}

		responseObserver.onNext(response.build());
		responseObserver.onCompleted();
	}

	@Override
	public void markAllRead(MarkAllReadRequest request, StreamObserver<MarkAllReadResponse> responseObserver) {
		UUID userId = UUID.fromString(request.getUserId());
		boolean success = notificationQueryService.markAllRead(userId);
		responseObserver.onNext(MarkAllReadResponse.newBuilder().setSuccess(success).build());
		responseObserver.onCompleted();
	}

	@Override
	public void getUnreadCount(GetUnreadCountRequest request, StreamObserver<GetUnreadCountResponse> responseObserver) {
		UUID userId = UUID.fromString(request.getUserId());
		long count = notificationQueryService.getUnreadCount(userId);
		responseObserver.onNext(GetUnreadCountResponse.newBuilder().setCount(count).build());
		responseObserver.onCompleted();
	}

	private NotificationProto toProto(Notification n) {
		NotificationProto.Builder builder = NotificationProto.newBuilder().setId(n.getId().toString())
				.setUserId(n.getUserId().toString()).setActorId(n.getActorId().toString()).setType(n.getType())
				.setEntityId(n.getEntityId()).setEntityType(n.getEntityType()).setIsRead(n.isRead())
				.setCreatedAt(n.getCreatedAt().toString());
		if (n.getParentEntityId() != null) {
			builder.setParentEntityId(n.getParentEntityId());
		}
		return builder.build();
	}
}
