package uni.gateway.dto.notification;

import java.util.List;

public record NotificationPageDto(List<NotificationDto> notifications, long total) {
}
