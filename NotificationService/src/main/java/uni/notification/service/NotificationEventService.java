package uni.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.notification.entity.Notification;
import uni.notification.entity.ProcessedEvent;
import uni.notification.kafka.EventEnvelope;
import uni.notification.repository.NotificationRepository;
import uni.notification.repository.ProcessedEventRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationEventService {

	private final NotificationRepository notificationRepository;
	private final ProcessedEventRepository processedEventRepository;
	private final ObjectMapper objectMapper;

	@Transactional
	public void processRaw(String raw) {
		EventEnvelope event = parse(raw);
		if (event.eventId() == null || event.eventId().isBlank()) {
			log.warn("Event with blank eventId skipped");
			return;
		}
		if (processedEventRepository.existsByEventId(event.eventId())) {
			return;
		}

		List<Notification> notifications = switch (event.eventType()) {
			case "POST_CREATED" -> onPostCreated(event);
			case "POST_LIKED" -> onPostLiked(event);
			case "COMMENT_CREATED" -> onCommentCreated(event);
			case "COMMENT_LIKED" -> onCommentLiked(event);
			case "USER_FOLLOWED" -> onUserFollowed(event);
			case "ADMIN_GRANTED" -> onAdminGranted(event);
			case "USER_BANNED" -> onUserBanned(event);
			default -> {
				log.debug("Ignoring event type {}", event.eventType());
				yield List.of();
			}
		};

		if (!notifications.isEmpty()) {
			notificationRepository.saveAll(notifications);
		}

		processedEventRepository
				.save(ProcessedEvent.builder().eventId(event.eventId()).processedAt(LocalDateTime.now()).build());
	}

	private List<Notification> onPostCreated(EventEnvelope event) {
		String postId = text(event.payload(), "postId");
		String authorId = text(event.payload(), "authorId");
		if (postId == null || authorId == null)
			return List.of();

		List<String> mentionedUserIds = stringList(event.payload(), "mentionedUserIds");
		List<Notification> result = new ArrayList<>();
		LocalDateTime now = LocalDateTime.now();
		for (String mentionedUserId : mentionedUserIds) {
			if (mentionedUserId.equals(authorId))
				continue;
			result.add(buildNotification(mentionedUserId, authorId, "MENTION_IN_POST", postId, "POST", now));
		}
		return result;
	}

	private List<Notification> onPostLiked(EventEnvelope event) {
		String postId = text(event.payload(), "postId");
		String authorId = text(event.payload(), "authorId");
		String actorId = text(event.payload(), "actorId");
		if (postId == null || authorId == null || actorId == null)
			return List.of();
		if (actorId.equals(authorId))
			return List.of();

		return List.of(buildNotification(authorId, actorId, "LIKE_POST", postId, "POST", LocalDateTime.now()));
	}

	private List<Notification> onCommentCreated(EventEnvelope event) {
		String commentId = text(event.payload(), "commentId");
		String postId = text(event.payload(), "postId");
		String authorId = text(event.payload(), "authorId");
		String postAuthorId = text(event.payload(), "postAuthorId");
		String parentCommentId = text(event.payload(), "parentCommentId");
		String parentAuthorId = text(event.payload(), "parentAuthorId");
		if (commentId == null || postId == null || authorId == null)
			return List.of();

		List<String> mentionedUserIds = stringList(event.payload(), "mentionedUserIds");
		List<Notification> result = new ArrayList<>();
		LocalDateTime now = LocalDateTime.now();

		if (parentCommentId != null && parentAuthorId != null && !parentAuthorId.equals(authorId)) {
			result.add(
					buildNotification(parentAuthorId, authorId, "REPLY_TO_COMMENT", commentId, "COMMENT", postId, now));
		} else if (postAuthorId != null && !postAuthorId.equals(authorId)) {
			result.add(buildNotification(postAuthorId, authorId, "COMMENT_ON_POST", commentId, "COMMENT", postId, now));
		}

		for (String mentionedUserId : mentionedUserIds) {
			if (mentionedUserId.equals(authorId))
				continue;
			boolean alreadyNotified = result.stream().anyMatch(n -> n.getUserId().toString().equals(mentionedUserId));
			if (!alreadyNotified) {
				result.add(buildNotification(mentionedUserId, authorId, "MENTION_IN_COMMENT", commentId, "COMMENT",
						postId, now));
			}
		}
		return result;
	}

	private List<Notification> onCommentLiked(EventEnvelope event) {
		String commentId = text(event.payload(), "commentId");
		String postId = text(event.payload(), "postId");
		String commentAuthorId = text(event.payload(), "commentAuthorId");
		String actorId = text(event.payload(), "actorId");
		if (commentId == null || commentAuthorId == null || actorId == null)
			return List.of();
		if (actorId.equals(commentAuthorId))
			return List.of();

		return List.of(buildNotification(commentAuthorId, actorId, "LIKE_COMMENT", commentId, "COMMENT", postId,
				LocalDateTime.now()));
	}

	private List<Notification> onUserFollowed(EventEnvelope event) {
		String subscriberId = text(event.payload(), "subscriberId");
		String targetUserId = text(event.payload(), "targetUserId");
		if (subscriberId == null || targetUserId == null)
			return List.of();
		if (subscriberId.equals(targetUserId))
			return List.of();

		return List
				.of(buildNotification(targetUserId, subscriberId, "FOLLOW", subscriberId, "USER", LocalDateTime.now()));
	}

	private List<Notification> onAdminGranted(EventEnvelope event) {
		String targetUserId = text(event.payload(), "targetUserId");
		String granterId = text(event.payload(), "granterId");
		if (targetUserId == null || granterId == null)
			return List.of();

		return List.of(
				buildNotification(targetUserId, granterId, "ADMIN_GRANTED", targetUserId, "USER", LocalDateTime.now()));
	}

	private List<Notification> onUserBanned(EventEnvelope event) {
		String targetUserId = text(event.payload(), "targetUserId");
		String moderatorId = text(event.payload(), "moderatorId");
		if (targetUserId == null || moderatorId == null)
			return List.of();

		return List
				.of(buildNotification(targetUserId, moderatorId, "BANNED", targetUserId, "USER", LocalDateTime.now()));
	}

	private Notification buildNotification(String userId, String actorId, String type, String entityId,
			String entityType, LocalDateTime now) {
		return buildNotification(userId, actorId, type, entityId, entityType, null, now);
	}

	private Notification buildNotification(String userId, String actorId, String type, String entityId,
			String entityType, String parentEntityId, LocalDateTime now) {
		return Notification.builder().id(UUID.randomUUID()).userId(UUID.fromString(userId))
				.actorId(UUID.fromString(actorId)).type(type).entityId(entityId).entityType(entityType)
				.parentEntityId(parentEntityId).isRead(false).createdAt(now).build();
	}

	private EventEnvelope parse(String raw) {
		try {
			return objectMapper.readValue(raw, EventEnvelope.class);
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to deserialize event", e);
		}
	}

	private static String text(JsonNode node, String field) {
		if (node == null || node.get(field) == null || node.get(field).isNull())
			return null;
		String value = node.get(field).asText();
		return value == null || value.isBlank() ? null : value;
	}

	private static List<String> stringList(JsonNode node, String field) {
		if (node == null || node.get(field) == null || !node.get(field).isArray())
			return List.of();
		List<String> result = new ArrayList<>();
		for (JsonNode item : node.get(field)) {
			String val = item.asText(null);
			if (val != null && !val.isBlank())
				result.add(val);
		}
		return result;
	}
}
