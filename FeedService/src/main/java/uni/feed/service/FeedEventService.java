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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedEventService {

	private static final Duration DEDUP_TTL = Duration.ofDays(7);

	private final ObjectMapper objectMapper;
	private final FeedRedisRepository redisRepository;

	@Value("${app.feed.author-window-size:1000}")
	private long authorWindowSize;

	@Value("${app.feed.uni-window-size:5000}")
	private long uniWindowSize;

	@Value("${app.feed.trending-window-size:5000}")
	private long popularWindowSize;

	@Value("${app.feed.trending-like-boost-ms:3600000}")
	private long trendingLikeBoostMs;

	public void processRaw(String raw) {
		EventEnvelope event = parse(raw);
		if (event.eventId() == null || event.eventId().isBlank()) {
			throw new IllegalArgumentException("eventId is missing");
		}

		if (redisRepository.isEventProcessed(event.eventId())) {
			return;
		}

		switch (event.eventType()) {
			case "POST_CREATED" -> onPostCreated(event);
			case "POST_DELETED" -> onPostDeleted(event);
			case "POST_LIKED" -> onPostLiked(event);
			case "POST_UNLIKED" -> onPostUnliked(event);
			case "USER_FOLLOWED" -> onUserFollowed(event);
			case "USER_UNFOLLOWED" -> onUserUnfollowed(event);
			case "USER_DELETED", "USER_PERMANENT_BANNED" -> onUserDeleted(event);
			default -> log.debug("Ignore event type {}", event.eventType());
		}

		redisRepository.markEventProcessed(event.eventId(), DEDUP_TTL);
	}

	private EventEnvelope parse(String raw) {
		try {
			return objectMapper.readValue(raw, EventEnvelope.class);
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to deserialize event", e);
		}
	}

	private void onPostCreated(EventEnvelope event) {
		String authorId = text(event.payload(), "authorId");
		String postId = text(event.payload(), "postId");
		Long universityId = parseNullableLong(text(event.payload(), "authorUniversityId"));
		if (universityId == null) {
			universityId = parseNullableLong(text(event.payload(), "universityId"));
		}
		Long topicId = parseNullableLong(text(event.payload(), "topicId"));
		Long parentTopicId = parseNullableLong(text(event.payload(), "parentTopicId"));
		Long facultyId = parseNullableLong(text(event.payload(), "facultyId"));
		Long programId = parseNullableLong(text(event.payload(), "programId"));
		if (authorId == null || postId == null) {
			throw new IllegalArgumentException("POST_CREATED payload is incomplete");
		}

		double score = score(event.occurredAt());

		redisRepository.addPostToAuthorFeed(authorId, postId, score);
		redisRepository.trimAuthorFeed(authorId, authorWindowSize);
		addScopedChronological(postId, score, universityId, facultyId, programId, topicId, parentTopicId);
		if (universityId == null) {
			redisRepository.addPostToOutsideFeed(postId, score);
			redisRepository.trimOutsideFeed(uniWindowSize);
		}

		double trendingScore = trendingScore(score, 0);
		redisRepository.addPostToPopularFeed(postId, trendingScore);
		redisRepository.trimPopularFeed(popularWindowSize);
		addScopedPopular(postId, trendingScore, universityId, facultyId, programId, topicId, parentTopicId);
		if (universityId == null) {
			redisRepository.addPostToOutsidePopularFeed(postId, trendingScore);
			redisRepository.trimOutsidePopularFeed(popularWindowSize);
		}

		Set<String> followers = redisRepository.findFollowers(authorId);
		for (String followerId : followers) {
			redisRepository.addPostToUserFeed(followerId, postId, score);
		}
	}

	private void onPostDeleted(EventEnvelope event) {
		String authorId = text(event.payload(), "authorId");
		String postId = text(event.payload(), "postId");
		Long universityId = parseNullableLong(text(event.payload(), "authorUniversityId"));
		if (universityId == null) {
			universityId = parseNullableLong(text(event.payload(), "universityId"));
		}
		Long topicId = parseNullableLong(text(event.payload(), "topicId"));
		Long parentTopicId = parseNullableLong(text(event.payload(), "parentTopicId"));
		Long facultyId = parseNullableLong(text(event.payload(), "facultyId"));
		Long programId = parseNullableLong(text(event.payload(), "programId"));
		if (postId == null) {
			throw new IllegalArgumentException("POST_DELETED payload is incomplete");
		}

		redisRepository.removePostFromPopularFeed(postId);
		removeScopedChronological(postId, universityId, facultyId, programId, topicId, parentTopicId);
		removeScopedPopular(postId, universityId, facultyId, programId, topicId, parentTopicId);
		if (universityId == null) {
			redisRepository.removePostFromOutsideFeed(postId);
			redisRepository.removePostFromOutsidePopularFeed(postId);
		}
		if (authorId != null) {
			redisRepository.removePostFromAuthorFeed(authorId, postId);
			for (String followerId : redisRepository.findFollowers(authorId)) {
				redisRepository.removePostsFromUserFeed(followerId, Set.of(postId));
			}
		}
	}

	private void onPostLiked(EventEnvelope event) {
		String postId = text(event.payload(), "postId");
		String likesCountStr = text(event.payload(), "likesCount");
		String createdAtMsStr = text(event.payload(), "createdAtMs");
		Long universityId = parseNullableLong(text(event.payload(), "authorUniversityId"));
		if (universityId == null) {
			universityId = parseNullableLong(text(event.payload(), "universityId"));
		}
		Long topicId = parseNullableLong(text(event.payload(), "topicId"));
		Long parentTopicId = parseNullableLong(text(event.payload(), "parentTopicId"));
		Long facultyId = parseNullableLong(text(event.payload(), "facultyId"));
		Long programId = parseNullableLong(text(event.payload(), "programId"));
		if (postId == null) {
			throw new IllegalArgumentException("POST_LIKED payload is incomplete");
		}

		long likesCount = likesCountStr != null ? parseLong(likesCountStr) : 0L;
		double createdAtMs = createdAtMsStr != null ? parseLong(createdAtMsStr) : score(event.occurredAt());
		double ts = trendingScore(createdAtMs, likesCount);

		redisRepository.addPostToPopularFeed(postId, ts);
		redisRepository.trimPopularFeed(popularWindowSize);
		addScopedPopular(postId, ts, universityId, facultyId, programId, topicId, parentTopicId);
		if (universityId == null) {
			redisRepository.addPostToOutsidePopularFeed(postId, ts);
			redisRepository.trimOutsidePopularFeed(popularWindowSize);
		}
	}

	private void onPostUnliked(EventEnvelope event) {
		String postId = text(event.payload(), "postId");
		String likesCountStr = text(event.payload(), "likesCount");
		String createdAtMsStr = text(event.payload(), "createdAtMs");
		Long universityId = parseNullableLong(text(event.payload(), "authorUniversityId"));
		if (universityId == null) {
			universityId = parseNullableLong(text(event.payload(), "universityId"));
		}
		Long topicId = parseNullableLong(text(event.payload(), "topicId"));
		Long parentTopicId = parseNullableLong(text(event.payload(), "parentTopicId"));
		Long facultyId = parseNullableLong(text(event.payload(), "facultyId"));
		Long programId = parseNullableLong(text(event.payload(), "programId"));
		if (postId == null) {
			throw new IllegalArgumentException("POST_UNLIKED payload is incomplete");
		}

		long likesCount = likesCountStr != null ? parseLong(likesCountStr) : 0L;
		double createdAtMs = createdAtMsStr != null ? parseLong(createdAtMsStr) : score(event.occurredAt());
		double ts = trendingScore(createdAtMs, likesCount);

		redisRepository.addPostToPopularFeed(postId, ts);
		addScopedPopular(postId, ts, universityId, facultyId, programId, topicId, parentTopicId);
		if (universityId == null) {
			redisRepository.addPostToOutsidePopularFeed(postId, ts);
		}
	}

	private void onUserFollowed(EventEnvelope event) {
		String subscriberId = text(event.payload(), "subscriberId");
		String targetUserId = text(event.payload(), "targetUserId");

		if (subscriberId == null || targetUserId == null || Objects.equals(subscriberId, targetUserId)) {
			throw new IllegalArgumentException("USER_FOLLOWED payload is invalid");
		}

		redisRepository.addFollowingRelation(subscriberId, targetUserId);

		Map<String, Double> latestPosts = redisRepository.findLatestAuthorPostsWithScores(targetUserId, 0, 99);
		for (Map.Entry<String, Double> entry : latestPosts.entrySet()) {
			redisRepository.addPostToUserFeed(subscriberId, entry.getKey(), entry.getValue());
		}
	}

	private void onUserUnfollowed(EventEnvelope event) {
		String subscriberId = text(event.payload(), "subscriberId");
		String targetUserId = text(event.payload(), "targetUserId");

		if (subscriberId == null || targetUserId == null || Objects.equals(subscriberId, targetUserId)) {
			throw new IllegalArgumentException("USER_UNFOLLOWED payload is invalid");
		}

		redisRepository.removeFollowingRelation(subscriberId, targetUserId);

		Map<String, Double> latestPosts = redisRepository.findLatestAuthorPostsWithScores(targetUserId, 0, -1);
		if (!latestPosts.isEmpty()) {
			redisRepository.removePostsFromUserFeed(subscriberId, latestPosts.keySet());
		}
	}

	private void onUserDeleted(EventEnvelope event) {
		String userId = event.aggregateId();
		if (userId == null || userId.isBlank()) {
			userId = text(event.payload(), "userId");
		}
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("USER_DELETED payload is invalid");
		}
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

	private void addScopedChronological(String postId, double score, Long universityId, Long facultyId, Long programId,
			Long topicId, Long parentTopicId) {
		if (universityId == null) {
			return;
		}
		redisRepository.addPostToUniversityFeed(universityId, postId, score);
		if (facultyId != null) {
			redisRepository.addPostToUniversityTopicFeed(universityId, facultyId, postId, score);
			redisRepository.trimUniversityTopicFeed(universityId, facultyId, uniWindowSize);
		} else if (parentTopicId != null) {
			redisRepository.addPostToUniversityTopicFeed(universityId, parentTopicId, postId, score);
			redisRepository.trimUniversityTopicFeed(universityId, parentTopicId, uniWindowSize);
		}
		if (programId != null) {
			redisRepository.addPostToUniversitySubtopicFeed(universityId, programId, postId, score);
			redisRepository.trimUniversitySubtopicFeed(universityId, programId, uniWindowSize);
		} else if (topicId != null && parentTopicId != null && !topicId.equals(parentTopicId)) {
			redisRepository.addPostToUniversitySubtopicFeed(universityId, topicId, postId, score);
			redisRepository.trimUniversitySubtopicFeed(universityId, topicId, uniWindowSize);
		}
		redisRepository.trimUniversityFeed(universityId, uniWindowSize);
	}

	private void removeScopedChronological(String postId, Long universityId, Long facultyId, Long programId,
			Long topicId, Long parentTopicId) {
		if (universityId == null) {
			return;
		}
		redisRepository.removePostFromUniversityFeed(universityId, postId);
		if (facultyId != null) {
			redisRepository.removePostFromUniversityTopicFeed(universityId, facultyId, postId);
		} else if (parentTopicId != null) {
			redisRepository.removePostFromUniversityTopicFeed(universityId, parentTopicId, postId);
		}
		if (programId != null) {
			redisRepository.removePostFromUniversitySubtopicFeed(universityId, programId, postId);
		} else if (topicId != null && parentTopicId != null && !topicId.equals(parentTopicId)) {
			redisRepository.removePostFromUniversitySubtopicFeed(universityId, topicId, postId);
		}
	}

	private void addScopedPopular(String postId, double trendingScore, Long universityId, Long facultyId,
			Long programId, Long topicId, Long parentTopicId) {
		if (universityId == null) {
			return;
		}
		redisRepository.addPostToUniversityPopularFeed(universityId, postId, trendingScore);
		if (facultyId != null) {
			redisRepository.addPostToUniversityTopicPopularFeed(universityId, facultyId, postId, trendingScore);
			redisRepository.trimUniversityTopicPopularFeed(universityId, facultyId, popularWindowSize);
		} else if (parentTopicId != null) {
			redisRepository.addPostToUniversityTopicPopularFeed(universityId, parentTopicId, postId, trendingScore);
			redisRepository.trimUniversityTopicPopularFeed(universityId, parentTopicId, popularWindowSize);
		}
		if (programId != null) {
			redisRepository.addPostToUniversitySubtopicPopularFeed(universityId, programId, postId, trendingScore);
			redisRepository.trimUniversitySubtopicPopularFeed(universityId, programId, popularWindowSize);
		} else if (topicId != null && parentTopicId != null && !topicId.equals(parentTopicId)) {
			redisRepository.addPostToUniversitySubtopicPopularFeed(universityId, topicId, postId, trendingScore);
			redisRepository.trimUniversitySubtopicPopularFeed(universityId, topicId, popularWindowSize);
		}
		redisRepository.trimUniversityPopularFeed(universityId, popularWindowSize);
	}

	private void removeScopedPopular(String postId, Long universityId, Long facultyId, Long programId, Long topicId,
			Long parentTopicId) {
		if (universityId == null) {
			return;
		}
		redisRepository.removePostFromUniversityPopularFeed(universityId, postId);
		if (facultyId != null) {
			redisRepository.removePostFromUniversityTopicPopularFeed(universityId, facultyId, postId);
		} else if (parentTopicId != null) {
			redisRepository.removePostFromUniversityTopicPopularFeed(universityId, parentTopicId, postId);
		}
		if (programId != null) {
			redisRepository.removePostFromUniversitySubtopicPopularFeed(universityId, programId, postId);
		} else if (topicId != null && parentTopicId != null && !topicId.equals(parentTopicId)) {
			redisRepository.removePostFromUniversitySubtopicPopularFeed(universityId, topicId, postId);
		}
	}
}
