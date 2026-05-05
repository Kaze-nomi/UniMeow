package uni.notification.service;

import uni.notification.entity.Notification;

import java.util.List;

public record NotificationPageResult(List<Notification> notifications, long total) {
}
