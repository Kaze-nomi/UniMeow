package uni.gateway.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;
import uni.gateway.dto.notification.NotificationDto;
import uni.gateway.dto.notification.NotificationPageDto;
import uni.gateway.dto.user.UserDto;
import uni.gateway.grpc.NotificationGrpcClient;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.notification.NotificationProto;

import javax.security.auth.login.CredentialException;
import java.nio.file.AccessDeniedException;

@Controller
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationGrpcClient notificationGrpcClient;
	private final UserGrpcClient userGrpcClient;

	@QueryMapping
	public Mono<NotificationPageDto> getNotifications(@Argument(name = "page") Integer page,
			@Argument(name = "size") Integer size, @ContextValue(name = "userId", required = false) String userId) {
		if (userId == null)
			return Mono.error(new CredentialException("Authentication required"));
		int p = page != null ? page : 0;
		int s = size != null ? size : 20;
		return requireActiveUser(userId).flatMap(activeId -> notificationGrpcClient.getNotifications(activeId, p, s))
				.map(resp -> new NotificationPageDto(resp.getNotificationsList().stream().map(this::toDto).toList(),
						resp.getTotal()));
	}

	@QueryMapping
	public Mono<Long> getUnreadNotificationCount(@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null)
			return Mono.error(new CredentialException("Authentication required"));
		return requireActiveUser(userId).flatMap(notificationGrpcClient::getUnreadCount).map(r -> r.getCount());
	}

	@MutationMapping
	public Mono<Boolean> markAllNotificationsRead(@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null)
			return Mono.error(new CredentialException("Authentication required"));
		return requireActiveUser(userId).flatMap(notificationGrpcClient::markAllRead).map(r -> r.getSuccess());
	}

	@SchemaMapping(typeName = "Notification", field = "actor")
	public Mono<UserDto> actor(NotificationDto notification) {
		return userGrpcClient.getUserById(notification.actorId()).map(this::toUserDto);
	}

	private NotificationDto toDto(NotificationProto proto) {
		return new NotificationDto(proto.getId(), proto.getUserId(), proto.getActorId(), null, proto.getType(),
				proto.getEntityId(), proto.getEntityType(),
				proto.hasParentEntityId() ? proto.getParentEntityId() : null, proto.getIsRead(), proto.getCreatedAt());
	}

	private Mono<String> requireActiveUser(String userId) {
		return userGrpcClient.getUserById(userId).flatMap(user -> {
			if (user.getIsBanned()) {
				return Mono.error(new AccessDeniedException("User is banned"));
			}
			return Mono.just(userId);
		});
	}

	private UserDto toUserDto(uni.grpc.user.UserResponse r) {
		return UserDto.builder().id(r.getId()).emailGoogle(r.getEmailGoogle())
				.username(r.getUsername().isBlank() ? null : r.getUsername()).name(r.getName())
				.surname(r.getSurname().isBlank() ? null : r.getSurname())
				.avatarUrl(r.getAvatarUrl().isBlank() ? null : r.getAvatarUrl()).isAdmin(r.getIsAdmin())
				.isBanned(r.getIsBanned()).createdAt(r.getCreatedAt()).isStudentVerified(r.getIsStudentVerified())
				.isEmployeeVerified(r.getIsEmployeeVerified()).build();
	}
}
