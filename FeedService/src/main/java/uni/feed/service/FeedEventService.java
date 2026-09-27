package uni.feed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uni.feed.events.EventEnvelope;
import uni.feed.repository.FeedRedisRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedEventService {

	private static final Duration DEDUP_TTL = Duration.ofDays(7);

	private final ObjectMapper objectMapper;
	private final FeedRedisRepository redisRepository;

	@Value("${app.feed.author-window-size:1000}")
	private long authorWindowSize;

	@Value("${app.feed.user-window-size:1000}")
	private long userWindowSize;

	@Value("${app.feed.uni-window-size:5000}")
	private long uniWindowSize;

	@Value("${app.feed.trending-window-size:5000}")
	private long popularWindowSize;

	@Value("${app.feed.trending-like-boost-ms:3600000}")
	private long trendingLikeBoostMs;

	public void processRaw(String raw) {
		EventEnvelope event = parse(raw);
		if (event == null) throw new IllegalArgumentException("Event envelope is missing");
		if (event.eventType() == null || event.eventType().isBlank() || event.payload() == null || !event.payload().isObject()) {
			throw new IllegalArgumentException("Event type or payload is invalid");
		}
		if (event.eventId() == null || event.eventId().isBlank()) {
			throw new IllegalArgumentException("eventId is missing");
		}

		if (redisRepository.isEventProcessed(event.eventId())) {
			return;
		}

		switch (event.eventType()) {
			case "POST_CREATED", "POST_DELETED", "POST_LIKED", "POST_UNLIKED" -> onPostEvent(event);
			case "USER_FOLLOWED", "USER_UNFOLLOWED" -> onFollowingEvent(event);
			case "USER_DELETED", "USER_PERMANENT_BANNED" -> onUserDeleted(event);
			default -> log.atDebug().addKeyValue("event", "event_ignored")
					.addKeyValue("eventId", event.eventId()).addKeyValue("eventType", event.eventType())
					.log("Ignoring event type");
		}

		redisRepository.markEventProcessed(event.eventId(), DEDUP_TTL);
		log.atInfo().addKeyValue("event", "event_applied").addKeyValue("eventId", event.eventId())
				.addKeyValue("eventType", event.eventType()).addKeyValue("aggregateId", event.aggregateId())
				.log("Feed event applied");
	}

	private EventEnvelope parse(String raw) {
		try {
			return objectMapper.readValue(raw, EventEnvelope.class);
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to deserialize event", e);
		}
	}

	private void onPostEvent(EventEnvelope event) {
		String postId = text(event.payload(), "postId");
		String authorId = text(event.payload(), "authorId");
		boolean created = event.eventType().equals("POST_CREATED");
		boolean deleted = event.eventType().equals("POST_DELETED");
		if (postId == null || (created && authorId == null)) {
			throw new IllegalArgumentException(event.eventType() + " payload is incomplete");
		}
		if (deleted) redisRepository.markPostDeleted(postId, DEDUP_TTL);

		String createdAtMs = text(event.payload(), "createdAtMs");
		double chronological = created || createdAtMs == null ? score(event.occurredAt()) : parseLong(createdAtMs);
		String likes = text(event.payload(), "likesCount");
		double popular = trendingScore(chronological, created || likes == null ? 0 : parseLong(likes));
		applyPost(event, "feed:popular", postId, authorId, null, popular, popularWindowSize);
		for (String feed : scopedFeeds(event)) {
			applyPost(event, feed + ":popular", postId, authorId, null, popular, popularWindowSize);
			if (created || deleted) applyPost(event, feed, postId, authorId, null, chronological, uniWindowSize);
		}
		if ((created || deleted) && authorId != null) {
			applyPost(event, "feed:author:" + authorId, postId, authorId, null, chronological, authorWindowSize);
			for (String followerId : redisRepository.findFollowers(authorId)) {
				applyPost(event, "feed:user:" + followerId, postId, authorId, followerId, chronological, userWindowSize);
			}
		}
	}

	private List<String> scopedFeeds(EventEnvelope event) {
		Long universityId = parseNullableLong(text(event.payload(), "authorUniversityId"));
		if (universityId == null) universityId = parseNullableLong(text(event.payload(), "universityId"));
		if (universityId == null) return List.of("feed:outside");
		String university = "feed:uni:" + universityId;
		List<String> feeds = new ArrayList<>(List.of(university));
		Long facultyId = parseNullableLong(text(event.payload(), "facultyId"));
		Long parentTopicId = parseNullableLong(text(event.payload(), "parentTopicId"));
		if (facultyId == null) facultyId = parentTopicId;
		if (facultyId != null) feeds.add(university + ":topic:" + facultyId);
		Long programId = parseNullableLong(text(event.payload(), "programId"));
		Long topicId = parseNullableLong(text(event.payload(), "topicId"));
		if (programId == null && topicId != null && parentTopicId != null && !topicId.equals(parentTopicId)) {
			programId = topicId;
		}
		if (programId != null) feeds.add(university + ":subtopic:" + programId);
		return feeds;
	}

	private void onFollowingEvent(EventEnvelope event) {
		String subscriberId = text(event.payload(), "subscriberId");
		String targetUserId = text(event.payload(), "targetUserId");
		if (subscriberId == null || targetUserId == null || Objects.equals(subscriberId, targetUserId)) {
			throw new IllegalArgumentException(event.eventType() + " payload is invalid");
		}
		redisRepository.applyFollowingEvent(event, subscriberId, targetUserId, DEDUP_TTL);
		long last = event.eventType().equals("USER_FOLLOWED") ? 99 : -1;
		for (Map.Entry<String, Double> post : redisRepository.findLatestAuthorPostsWithScores(targetUserId, 0, last).entrySet()) {
			applyPost(event, "feed:user:" + subscriberId, post.getKey(), targetUserId, subscriberId, post.getValue(), userWindowSize);
		}
	}

	private void applyPost(EventEnvelope event, String feed, String postId, String authorId, String followerId,
			double score, long maxSize) {
		redisRepository.applyPostProjection(event, feed, postId, authorId, followerId, score, maxSize, DEDUP_TTL);
	}

	private void onUserDeleted(EventEnvelope event) {
		String userId = event.aggregateId();
		if (userId == null || userId.isBlank()) {
			userId = text(event.payload(), "userId");
		}
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("USER_DELETED payload is invalid");
		}
		redisRepository.markUserDeleted(userId, DEDUP_TTL);
		redisRepository.removeUserFromAllFeeds(userId);
	}

	private static String text(JsonNode node, String field) {
		if (node == null || node.get(field) == null || node.get(field).isNull())
			return null;
		String value = node.get(field).asText();
		return value == null || value.isBlank() ? null : value;
	}

	private static double score(String occurredAt) {
		try {
			return Instant.parse(occurredAt).toEpochMilli();
		} catch (Exception ignored) {
			return Instant.now().toEpochMilli();
		}
	}

	private static long parseLong(String s) {
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private static Long parseNullableLong(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private double trendingScore(double createdAtMs, long likesCount) {
		return createdAtMs + likesCount * trendingLikeBoostMs;
	}

}
