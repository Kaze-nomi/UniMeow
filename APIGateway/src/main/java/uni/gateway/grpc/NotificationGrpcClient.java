package uni.gateway.grpc;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uni.grpc.notification.*;

@Service
public class NotificationGrpcClient {

	@GrpcClient("notification-service")
	private NotificationServiceGrpc.NotificationServiceBlockingStub stub;

	public Mono<NotificationListResponse> getNotifications(String userId, int page, int size) {
		return Mono
				.fromCallable(() -> stub.getNotifications(
						GetNotificationsRequest.newBuilder().setUserId(userId).setPage(page).setSize(size).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<MarkAllReadResponse> markAllRead(String userId) {
		return Mono.fromCallable(() -> stub.markAllRead(MarkAllReadRequest.newBuilder().setUserId(userId).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<GetUnreadCountResponse> getUnreadCount(String userId) {
		return Mono
				.fromCallable(() -> stub.getUnreadCount(GetUnreadCountRequest.newBuilder().setUserId(userId).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}
}
