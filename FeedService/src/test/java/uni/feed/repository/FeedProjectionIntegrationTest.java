package uni.feed.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import uni.feed.events.EventEnvelope;
import uni.feed.service.FeedEventService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("integration")
class FeedProjectionIntegrationTest {
	private static final Duration TTL = Duration.ofDays(7);
	private static final ObjectMapper JSON = new ObjectMapper();
	private static LettuceConnectionFactory connection;
	private static StringRedisTemplate redis;
	private FeedRedisRepository repository;
	private FeedEventService service;
	private String author;
	private String follower;
	private String post;

	@BeforeAll
	static void connect() {
		String port = System.getenv("REDIS_TEST_PORT");
		assertThat(port).as("REDIS_TEST_PORT is required").isNotBlank();
		RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(port));
		configuration.setDatabase(15);
		connection = new LettuceConnectionFactory(configuration);
		connection.afterPropertiesSet();
		connection.start();
		redis = new StringRedisTemplate(connection);
		assertThat(redis.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");
	}

	@BeforeEach
	void setup() {
		author = UUID.randomUUID().toString();
		follower = UUID.randomUUID().toString();
		post = UUID.randomUUID().toString();
		repository = spy(new FeedRedisRepository(redis));
		service = new FeedEventService(JSON, repository);
		ReflectionTestUtils.setField(service, "authorWindowSize", 1000L);
		ReflectionTestUtils.setField(service, "userWindowSize", 1000L);
		ReflectionTestUtils.setField(service, "uniWindowSize", 5000L);
		ReflectionTestUtils.setField(service, "popularWindowSize", 5000L);
		ReflectionTestUtils.setField(service, "trendingLikeBoostMs", 1L);
	}

	@AfterEach
	void removeTestMembers() {
		for (String key : List.of("feed:popular", "feed:outside", "feed:outside:popular")) {
			redis.opsForZSet().remove(key, post);
		}
		redis.delete(List.of("feed:author:" + author, "feed:user:" + follower,
				"feed:followers:" + author, "feed:following:" + follower));
	}

	@AfterAll
	static void disconnect() {
		if (connection != null) connection.destroy();
	}

	@Test
	void interruptedFanoutResumesWithoutLosingFollowers() throws Exception {
		repository.addFollowingRelation(follower, author);
		EventEnvelope created = postEvent("POST_CREATED", 0);
		doThrow(new IllegalStateException("interrupted Redis operation")).doCallRealMethod().when(repository)
				.applyPostProjection(any(), eq("feed:outside"), any(), any(), any(), anyDouble(), anyLong(), any());
		assertThatThrownBy(() -> process(created)).isInstanceOf(IllegalStateException.class);
		assertThat(repository.isEventProcessed(created.eventId())).isFalse();
		process(created);
		assertThat(score("feed:author:" + author)).isEqualTo(1000.0);
		assertThat(score("feed:user:" + follower)).isEqualTo(1000.0);
		assertThat(repository.isEventProcessed(created.eventId())).isTrue();
		assertThat(redis.getExpire("feed:processed:event:" + created.eventId())).isBetween(1L, TTL.toSeconds());
	}

	@Test
	void partialCreateReplayCannotResetNewerLikeScore() throws Exception {
		EventEnvelope created = postEvent("POST_CREATED", 0);
		repository.applyPostProjection(created, "feed:popular", post, author, null, 1000, 5000, TTL);
		process(postEvent("POST_LIKED", 7));
		process(created);
		assertThat(score("feed:popular")).isEqualTo(1007.0);
		assertThat(score("feed:outside:popular")).isEqualTo(1007.0);
		assertThat(score("feed:author:" + author)).isEqualTo(1000.0);
	}

	@Test
	void lateLikeAndCreateCannotResurrectDeletedPost() throws Exception {
		repository.addFollowingRelation(follower, author);
		process(postEvent("POST_CREATED", 0));
		process(postEvent("POST_DELETED", 0));
		process(postEvent("POST_LIKED", 9));
		process(postEvent("POST_CREATED", 0));
		for (String key : List.of("feed:popular", "feed:outside:popular", "feed:outside",
				"feed:author:" + author, "feed:user:" + follower)) {
			assertThat(score(key)).as(key).isNull();
		}
	}

	@Test
	void completedEventStopsOldConsumerWritingAnUnvisitedProjection() throws Exception {
		EventEnvelope liked = postEvent("POST_LIKED", 8);
		process(liked);
		process(postEvent("POST_UNLIKED", 2));
		repository.applyPostProjection(liked, "feed:popular", post, author, null, 1008, 5000, TTL);
		repository.applyPostProjection(liked, "feed:author:" + author, post, author, null, 1008, 1000, TTL);
		assertThat(score("feed:popular")).isEqualTo(1002.0);
		assertThat(score("feed:author:" + author)).isNull();
	}

	@Test
	void staleFollowBackfillCannotUndoUnfollowOrUserDeletion() throws Exception {
		process(postEvent("POST_CREATED", 0));
		EventEnvelope followed = relationEvent("USER_FOLLOWED");
		repository.applyFollowingEvent(followed, follower, author, TTL);
		process(relationEvent("USER_UNFOLLOWED"));
		repository.applyPostProjection(followed, "feed:user:" + follower, post, author, follower, 1000, 1000, TTL);
		assertThat(score("feed:user:" + follower)).isNull();
		process(relationEvent("USER_FOLLOWED"));
		assertThat(score("feed:user:" + follower)).isEqualTo(1000.0);
		process(event("USER_DELETED", Map.of("userId", author), author));
		process(relationEvent("USER_FOLLOWED"));
		process(postEvent("POST_CREATED", 0));
		assertThat(score("feed:user:" + follower)).isNull();
		assertThat(repository.findFollowers(author)).isEmpty();
	}

	private Double score(String key) {
		return redis.opsForZSet().score(key, post);
	}

	private EventEnvelope postEvent(String type, long likes) {
		return event(type, Map.of("postId", post, "authorId", author, "likesCount", likes, "createdAtMs", 1000), post);
	}

	private EventEnvelope relationEvent(String type) {
		return event(type, Map.of("subscriberId", follower, "targetUserId", author), follower + "->" + author);
	}

	private EventEnvelope event(String type, Map<String, Object> payload, String aggregate) {
		return new EventEnvelope(UUID.randomUUID().toString(), type, "1970-01-01T00:00:01Z", aggregate,
				JSON.valueToTree(payload), 1);
	}

	private void process(EventEnvelope event) throws Exception {
		service.processRaw(JSON.writeValueAsString(event));
	}
}
