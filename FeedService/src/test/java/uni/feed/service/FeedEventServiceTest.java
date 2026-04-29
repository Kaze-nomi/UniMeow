package uni.feed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import uni.feed.repository.FeedRedisRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedEventServiceTest {

	@Mock
	FeedRedisRepository redisRepository;

	@InjectMocks
	FeedEventService feedEventService;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(feedEventService, "objectMapper", new ObjectMapper());
		ReflectionTestUtils.setField(feedEventService, "popularWindowSize", 1000L);
		ReflectionTestUtils.setField(feedEventService, "trendingLikeBoostMs", 1L);
	}

	private static final String EVENT_ID = "event-123";
	private static final String AUTHOR_ID = "author-456";
	private static final String POST_ID = "post-789";
	private static final String SUBSCRIBER_ID = "user-111";
	private static final String TARGET_USER_ID = "user-222";

	private String buildEventJson(String eventType, String eventId, String payload) {
		return """
				{
				    "eventId": "%s",
				    "eventType": "%s",
				    "occurredAt": "%s",
				    "payload": %s
				}
				""".formatted(eventId, eventType, Instant.now().toString(), payload);
	}

	@Test
	void processRaw_throws_when_event_id_is_missing() {
		String json = """
				{
				    "eventType": "POST_CREATED",
				    "occurredAt": "%s",
				    "payload": {"authorId": "author-1", "postId": "post-1"}
				}
				""".formatted(Instant.now().toString());

		assertThatThrownBy(() -> feedEventService.processRaw(json)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("eventId");
	}

	@Test
	void processRaw_throws_when_event_id_is_blank() {
		String json = """
				{
				    "eventId": "   ",
				    "eventType": "POST_CREATED",
				    "occurredAt": "%s",
				    "payload": {"authorId": "author-1", "postId": "post-1"}
				}
				""".formatted(Instant.now().toString());

		assertThatThrownBy(() -> feedEventService.processRaw(json)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("eventId");
	}

	@Test
	void processRaw_skips_processing_if_event_already_processed() {
		String json = buildEventJson("POST_CREATED", EVENT_ID, """
				{"authorId": "%s", "postId": "%s"}
				""".formatted(AUTHOR_ID, POST_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(true);

		feedEventService.processRaw(json);

		verify(redisRepository, never()).addPostToAuthorFeed(any(), any(), anyDouble());
		verify(redisRepository, never()).addPostToGlobalFeed(any(), anyDouble());
		verify(redisRepository, never()).markEventProcessed(any(), any());
	}

	@Test
	void processRaw_calls_onPostCreated_for_post_created_event() {
		ReflectionTestUtils.setField(feedEventService, "authorWindowSize", 1000L);
		ReflectionTestUtils.setField(feedEventService, "globalWindowSize", 5000L);

		String json = buildEventJson("POST_CREATED", EVENT_ID, """
				{"authorId": "%s", "postId": "%s"}
				""".formatted(AUTHOR_ID, POST_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);
		when(redisRepository.findFollowers(AUTHOR_ID)).thenReturn(Set.of());

		feedEventService.processRaw(json);

		verify(redisRepository).addPostToAuthorFeed(eq(AUTHOR_ID), eq(POST_ID), anyDouble());
		verify(redisRepository).addPostToGlobalFeed(eq(POST_ID), anyDouble());
	}

	@Test
	void onPostCreated_updates_university_and_topic_feeds_when_scope_present() {
		ReflectionTestUtils.setField(feedEventService, "authorWindowSize", 1000L);
		ReflectionTestUtils.setField(feedEventService, "globalWindowSize", 5000L);
		ReflectionTestUtils.setField(feedEventService, "uniWindowSize", 5000L);

		String json = buildEventJson("POST_CREATED", EVENT_ID, """
				{"authorId":"%s","postId":"%s","authorUniversityId":"7","topicId":"11","parentTopicId":"5"}
				""".formatted(AUTHOR_ID, POST_ID));
		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);
		when(redisRepository.findFollowers(AUTHOR_ID)).thenReturn(Set.of());

		feedEventService.processRaw(json);

		verify(redisRepository).addPostToUniversityFeed(eq(7L), eq(POST_ID), anyDouble());
		verify(redisRepository).addPostToUniversityTopicFeed(eq(7L), eq(5L), eq(POST_ID), anyDouble());
		verify(redisRepository).addPostToUniversitySubtopicFeed(eq(7L), eq(11L), eq(POST_ID), anyDouble());
	}

	@Test
	void processRaw_throws_for_post_created_with_missing_author_id() {
		String json = buildEventJson("POST_CREATED", EVENT_ID, """
				{"postId": "%s"}
				""".formatted(POST_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		assertThatThrownBy(() -> feedEventService.processRaw(json)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("POST_CREATED");
	}

	@Test
	void processRaw_throws_for_post_created_with_missing_post_id() {
		String json = buildEventJson("POST_CREATED", EVENT_ID, """
				{"authorId": "%s"}
				""".formatted(AUTHOR_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		assertThatThrownBy(() -> feedEventService.processRaw(json)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("POST_CREATED");
	}

	@Test
	void onPostCreated_adds_post_to_all_follower_feeds() {
		ReflectionTestUtils.setField(feedEventService, "authorWindowSize", 1000L);
		ReflectionTestUtils.setField(feedEventService, "globalWindowSize", 5000L);

		String json = buildEventJson("POST_CREATED", EVENT_ID, """
				{"authorId": "%s", "postId": "%s"}
				""".formatted(AUTHOR_ID, POST_ID));

		Set<String> followers = Set.of("follower-1", "follower-2", "follower-3");
		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);
		when(redisRepository.findFollowers(AUTHOR_ID)).thenReturn(followers);

		feedEventService.processRaw(json);

		followers.forEach(
				followerId -> verify(redisRepository).addPostToUserFeed(eq(followerId), eq(POST_ID), anyDouble()));
	}

	@Test
	void onPostCreated_marks_event_as_processed() {
		ReflectionTestUtils.setField(feedEventService, "authorWindowSize", 1000L);
		ReflectionTestUtils.setField(feedEventService, "globalWindowSize", 5000L);

		String json = buildEventJson("POST_CREATED", EVENT_ID, """
				{"authorId": "%s", "postId": "%s"}
				""".formatted(AUTHOR_ID, POST_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);
		when(redisRepository.findFollowers(AUTHOR_ID)).thenReturn(Set.of());

		feedEventService.processRaw(json);

		verify(redisRepository).markEventProcessed(eq(EVENT_ID), any());
	}

	@Test
	void onPostDeleted_removes_post_from_global_author_and_popular_feeds() {
		String json = buildEventJson("POST_DELETED", EVENT_ID, """
				{"authorId": "%s", "postId": "%s"}
				""".formatted(AUTHOR_ID, POST_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		feedEventService.processRaw(json);

		verify(redisRepository).removePostFromGlobalFeed(POST_ID);
		verify(redisRepository).removePostFromAuthorFeed(AUTHOR_ID, POST_ID);
		verify(redisRepository).removePostFromPopularFeed(POST_ID);
	}

	@Test
	void onPostDeleted_removes_from_global_and_popular_when_author_id_missing() {
		String json = buildEventJson("POST_DELETED", EVENT_ID, """
				{"postId": "%s"}
				""".formatted(POST_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		feedEventService.processRaw(json);

		verify(redisRepository).removePostFromGlobalFeed(POST_ID);
		verify(redisRepository).removePostFromPopularFeed(POST_ID);
		verify(redisRepository, never()).removePostFromAuthorFeed(any(), any());
	}

	@Test
	void onPostLiked_adds_post_to_popular_feed_with_trending_score() {
		String json = buildEventJson("POST_LIKED", EVENT_ID, """
				{"postId": "%s", "authorId": "%s", "userId": "user-1", "likesCount": "5", "createdAtMs": "1000"}
				""".formatted(POST_ID, AUTHOR_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		feedEventService.processRaw(json);

		verify(redisRepository).addPostToPopularFeed(POST_ID, 1005.0);
		verify(redisRepository).trimPopularFeed(1000L);
	}

	@Test
	void onPostLiked_updates_scoped_popular_feeds_when_scope_present() {
		String json = buildEventJson("POST_LIKED", EVENT_ID,
				"""
						{"postId":"%s","likesCount":"5","createdAtMs":"1000","authorUniversityId":"7","topicId":"11","parentTopicId":"5"}
						"""
						.formatted(POST_ID));
		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		feedEventService.processRaw(json);

		verify(redisRepository).addPostToUniversityPopularFeed(7L, POST_ID, 1005.0);
		verify(redisRepository).addPostToUniversityTopicPopularFeed(7L, 5L, POST_ID, 1005.0);
		verify(redisRepository).addPostToUniversitySubtopicPopularFeed(7L, 11L, POST_ID, 1005.0);
	}

	@Test
	void onPostLiked_still_updates_popular_feed_when_likes_are_low() {
		String json = buildEventJson("POST_LIKED", EVENT_ID, """
				{"postId": "%s", "authorId": "%s", "userId": "user-1", "likesCount": "3", "createdAtMs": "1000"}
				""".formatted(POST_ID, AUTHOR_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		feedEventService.processRaw(json);

		verify(redisRepository).addPostToPopularFeed(POST_ID, 1003.0);
	}

	@Test
	void onPostLiked_throws_when_post_id_missing() {
		String json = buildEventJson("POST_LIKED", EVENT_ID, """
				{"authorId": "%s", "userId": "user-1", "likesCount": "5"}
				""".formatted(AUTHOR_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		assertThatThrownBy(() -> feedEventService.processRaw(json)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("POST_LIKED");
	}

	@Test
	void onPostUnliked_updates_popular_score() {
		String json = buildEventJson("POST_UNLIKED", EVENT_ID, """
				{"postId": "%s", "authorId": "%s", "userId": "user-1", "likesCount": "4", "createdAtMs": "1000"}
				""".formatted(POST_ID, AUTHOR_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		feedEventService.processRaw(json);

		verify(redisRepository).addPostToPopularFeed(POST_ID, 1004.0);
	}

	@Test
	void onPostUnliked_keeps_post_in_popular_feed_with_recalculated_score() {
		String json = buildEventJson("POST_UNLIKED", EVENT_ID, """
				{"postId": "%s", "authorId": "%s", "userId": "user-1", "likesCount": "0", "createdAtMs": "1000"}
				""".formatted(POST_ID, AUTHOR_ID));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		feedEventService.processRaw(json);

		verify(redisRepository).addPostToPopularFeed(POST_ID, 1000.0);
	}

	@Test
	void onPostDeleted_removes_from_scoped_feeds_when_scope_present() {
		String json = buildEventJson("POST_DELETED", EVENT_ID, """
				{"authorId":"%s","postId":"%s","authorUniversityId":"7","topicId":"11","parentTopicId":"5"}
				""".formatted(AUTHOR_ID, POST_ID));
		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		feedEventService.processRaw(json);

		verify(redisRepository).removePostFromUniversityFeed(7L, POST_ID);
		verify(redisRepository).removePostFromUniversityTopicFeed(7L, 5L, POST_ID);
		verify(redisRepository).removePostFromUniversitySubtopicFeed(7L, 11L, POST_ID);
		verify(redisRepository).removePostFromUniversityPopularFeed(7L, POST_ID);
	}

	@Test
	void onUserFollowed_adds_following_relation_and_posts() {
		ReflectionTestUtils.setField(feedEventService, "authorWindowSize", 1000L);
		ReflectionTestUtils.setField(feedEventService, "globalWindowSize", 5000L);

		String json = buildEventJson("USER_FOLLOWED", EVENT_ID, """
				{"subscriberId": "%s", "targetUserId": "%s"}
				""".formatted(SUBSCRIBER_ID, TARGET_USER_ID));

		Map<String, Double> latestPosts = Map.of("post1", 100.0, "post2", 200.0);
		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);
		when(redisRepository.findLatestAuthorPostsWithScores(TARGET_USER_ID, 0, 99)).thenReturn(latestPosts);

		feedEventService.processRaw(json);

		verify(redisRepository).addFollowingRelation(SUBSCRIBER_ID, TARGET_USER_ID);
		latestPosts.forEach((postId, score) -> verify(redisRepository).addPostToUserFeed(SUBSCRIBER_ID, postId, score));
	}

	@Test
	void onUserFollowed_throws_when_subscriber_equals_target() {
		String json = buildEventJson("USER_FOLLOWED", EVENT_ID, """
				{"subscriberId": "%s", "targetUserId": "%s"}
				""".formatted("same-user", "same-user"));

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		assertThatThrownBy(() -> feedEventService.processRaw(json)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("USER_FOLLOWED");
	}

	@Test
	void onUserUnfollowed_removes_following_relation_and_posts() {
		String json = buildEventJson("USER_UNFOLLOWED", EVENT_ID, """
				{"subscriberId": "%s", "targetUserId": "%s"}
				""".formatted(SUBSCRIBER_ID, TARGET_USER_ID));

		Map<String, Double> latestPosts = Map.of("post1", 100.0, "post2", 200.0);
		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);
		when(redisRepository.findLatestAuthorPostsWithScores(TARGET_USER_ID, 0, 499)).thenReturn(latestPosts);

		feedEventService.processRaw(json);

		verify(redisRepository).removeFollowingRelation(SUBSCRIBER_ID, TARGET_USER_ID);
		verify(redisRepository).removePostsFromUserFeed(SUBSCRIBER_ID, latestPosts.keySet());
	}

	@Test
	void processRaw_ignores_unknown_event_types() {
		String json = buildEventJson("UNKNOWN_EVENT", EVENT_ID, """
				{"data": "something"}
				""");

		when(redisRepository.isEventProcessed(EVENT_ID)).thenReturn(false);

		feedEventService.processRaw(json);

		verify(redisRepository).markEventProcessed(eq(EVENT_ID), any());
	}

	@Test
	void processRaw_throws_when_json_is_invalid() {
		String invalidJson = "not a json";

		assertThatThrownBy(() -> feedEventService.processRaw(invalidJson)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Failed to deserialize");
	}
}
