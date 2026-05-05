package uni.gateway.dto.notification;

import uni.gateway.dto.user.UserDto;

public record NotificationDto(String id, String userId, String actorId, UserDto actor, String type, String entityId,
		String entityType, String parentEntityId, boolean isRead, String createdAt) {
}
