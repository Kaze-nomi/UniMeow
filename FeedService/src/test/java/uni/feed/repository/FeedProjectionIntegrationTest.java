package uni.feed.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import uni.feed.service.FeedEventService;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Run against an isolated Redis: INTEGRATION_REDIS_PORT=16381. Never flushes a
 * DB.
 */
@Tag("integration")
class FeedProjectionIntegrationTest {

	static LettuceConnectionFactory connection;
	static StringRedisTemplate redis;
	FeedProjectionRepository projections;
	FeedEventService service;
	String author;
	String subscriber;
	String post;
	String prefix;

	@BeforeAll
	static void connect() {
		connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",
				Integer.parseInt(System.getenv("INTEGRATION_REDIS_PORT"))));
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
		redis.afterPropertiesSet();
	}

	@AfterAll
	static void disconnect() {
		connection.destroy();
	}

	@BeforeEach
	void setup() {
		prefix = UUID.randomUUID().toString();
		author = prefix + "-author";
		subscriber = prefix + "-subscriber";
		post = prefix + "-post";
		projections = new FeedProjectionRepository(redis);
		service = service(projections);
	}

	private FeedEventService service(FeedProjectionRepository repository) {
		FeedEventService result = new FeedEventService(new ObjectMapper(), repository);
		ReflectionTestUtils.setField(result, "authorWindowSize", 1000L);
		ReflectionTestUtils.setField(result, "userWindowSize", 1000L);
		ReflectionTestUtils.setField(result, "uniWindowSize", 5000L);
		ReflectionTestUtils.setField(result, "popularWindowSize", 5000L);
		ReflectionTestUtils.setField(result, "trendingLikeBoostMs", 1L);
		return result;
	}

	private String event(String id, String type, String payload) {
		return """
				{"eventId":"%s","eventType":"%s","occurredAt":"2026-01-01T00:00:00Z","payload":%s}
				""".formatted(prefix + id, type, payload);
	}

	private String postEvent(String id, String type, int likes) {
		return event(id, type,
				"""
						{"postId":"%s","authorId":"%s","createdAtMs":1000,"likesCount":%d,"authorUniversityId":7,"topicId":11,"parentTopicId":5}
						"""
						.formatted(post, author, likes));
	}

	private String relation(String id, String type) {
		return event(id, type, "{\"subscriberId\":\"" + subscriber + "\",\"targetUserId\":\"" + author + "\"}");
	}

	@Test
	void partialCreationResumesAfterNewerLikeWithoutOverwritingPopularity() {
		service.processRaw(relation("follow", "USER_FOLLOWED"));
		String other = prefix + "-other";
		projections.follow(other, author, projections.begin(prefix + "otherFollow"), 1000);
		FeedEventService failing = service(new FeedProjectionRepository(redis) {
			@Override
			public void fanout(String a, String p, double score, long limit) {
				deliver(a, subscriber, p, score, limit);
				throw new IllegalStateException("Simulated connection loss after first follower");
			}
		});
		String create = postEvent("create", "POST_CREATED", 0);
		assertThatThrownBy(() -> failing.processRaw(create)).isInstanceOf(IllegalStateException.class);
		assertThat(redis.opsForHash().get("feed:receipt:" + prefix + "create", "done")).isNull();
		service.processRaw(postEvent("like", "POST_LIKED", 9));
		service.processRaw(create);
		assertThat(redis.opsForZSet().score("feed:popular", post)).isEqualTo(1009.0);
		assertThat(redis.opsForZSet().score("feed:user:" + other, post)).isEqualTo(1000.0);
		assertThat(redis.opsForZSet().score("feed:uni:7:subtopic:11", post)).isEqualTo(1000.0);
		assertThat(redis.opsForHash().get("feed:receipt:" + prefix + "create", "done")).isEqualTo("1");
	}

	@Test
	void unfinishedFollowCannotUndoLaterUnfollow() {
		service.processRaw(postEvent("create", "POST_CREATED", 0));
		String follow = relation("follow", "USER_FOLLOWED");
		FeedEventService failing = service(new FeedProjectionRepository(redis) {
			@Override
			public void follow(String user, String a, long revision, long limit) {
				super.follow(user, a, revision, limit);
				throw new IllegalStateException("Crash before completion receipt");
			}
		});
		assertThatThrownBy(() -> failing.processRaw(follow)).isInstanceOf(IllegalStateException.class);
		service.processRaw(relation("unfollow", "USER_UNFOLLOWED"));
		service.processRaw(follow);
		assertThat(redis.opsForSet().isMember("feed:followers:" + author, subscriber)).isFalse();
		assertThat(redis.opsForZSet().score("feed:user:" + subscriber, post)).isNull();
	}

	@Test
	void deleteTombstoneRejectsPreviouslyUnseenCreateAndLike() {
		service.processRaw(relation("follow", "USER_FOLLOWED"));
		service.processRaw(postEvent("delete", "POST_DELETED", 0));
		service.processRaw(postEvent("create", "POST_CREATED", 0));
		service.processRaw(postEvent("like", "POST_LIKED", 5));
		assertThat(redis.opsForZSet().score("feed:popular", post)).isNull();
		assertThat(redis.opsForZSet().score("feed:user:" + subscriber, post)).isNull();
	}

	@Test
	void userDeletionAcrossTopicsBlocksLatePostsAndRelations() {
		service.processRaw(relation("follow", "USER_FOLLOWED"));
		service.processRaw(postEvent("create", "POST_CREATED", 0));
		service.processRaw(event("deleteUser", "USER_DELETED", "{\"userId\":\"" + author + "\"}"));
		service.processRaw(relation("lateFollow", "USER_FOLLOWED"));
		service.processRaw(postEvent("lateLike", "POST_LIKED", 5));
		assertThat(redis.opsForSet().isMember("feed:followers:" + author, subscriber)).isFalse();
		assertThat(redis.opsForZSet().score("feed:popular", post)).isNull();
		assertThat(redis.opsForZSet().score("feed:user:" + subscriber, post)).isNull();
	}

	@Test
	void concurrentDuplicateKeepsOneStableRevisionAndOneEffect() throws Exception {
		String create = postEvent("create", "POST_CREATED", 0);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> service.processRaw(create));
			var second = executor.submit(() -> service.processRaw(create));
			first.get();
			second.get();
		}
		assertThat(redis.opsForZSet().zCard("feed:author:" + author)).isEqualTo(1L);
		assertThat(redis.getExpire("feed:receipt:" + prefix + "create")).isEqualTo(-1L);
	}

	@Test
	void staleLikeDoesNotRegressNewerUnlikeAndLegacyReceiptBecomesPermanent() {
		long older = projections.begin(prefix + "like");
		service.processRaw(postEvent("unlike", "POST_UNLIKED", 1));
		projections.projectPost(post, author, older, 1000, 1008, false,
				List.of(new FeedProjectionRepository.Destination("feed:popular", 5000, true)));
		assertThat(redis.opsForZSet().score("feed:popular", post)).isEqualTo(1001.0);
		redis.opsForValue().set("feed:processed:event:" + prefix + "legacy", "1", Duration.ofSeconds(1));
		assertThat(projections.begin(prefix + "legacy")).isEqualTo(-1);
		assertThat(redis.getExpire("feed:receipt:" + prefix + "legacy")).isEqualTo(-1L);
	}

	@Test
	void previouslyUnseenCreationAfterLikeOnlyInitializesChronologyAndPreservesPopularity() {
		service.processRaw(relation("follow", "USER_FOLLOWED"));
		service.processRaw(postEvent("like", "POST_LIKED", 9));
		service.processRaw(postEvent("lateCreate", "POST_CREATED", 0));
		assertThat(redis.opsForZSet().score("feed:popular", post)).isEqualTo(1009.0);
		assertThat(redis.opsForZSet().score("feed:uni:7:popular", post)).isEqualTo(1009.0);
		assertThat(redis.opsForZSet().score("feed:uni:7", post)).isEqualTo(1000.0);
		assertThat(redis.opsForZSet().score("feed:user:" + subscriber, post)).isEqualTo(1000.0);
		service.processRaw(postEvent("unlike", "POST_UNLIKED", 0));
		assertThat(redis.opsForZSet().score("feed:popular", post)).isEqualTo(1000.0);
	}

	@Test
	void multipleScanBatchesResumeAfterFailureInMiddleOfFanout() {
		for (int i = 0; i < 270; i++) {
			projections.follow(prefix + "-follower-" + i, author, projections.begin(prefix + "-follow-" + i), 1000);
		}
		AtomicInteger deliveries = new AtomicInteger();
		FeedEventService failing = service(new FeedProjectionRepository(redis) {
			@Override
			public void deliver(String a, String user, String id, double score, long limit) {
				if (deliveries.incrementAndGet() == 140) {
					throw new IllegalStateException("Interrupted in second fanout batch");
				}
				super.deliver(a, user, id, score, limit);
			}
		});
		String create = postEvent("largeCreate", "POST_CREATED", 0);
		assertThatThrownBy(() -> failing.processRaw(create)).isInstanceOf(IllegalStateException.class);
		service.processRaw(create);
		for (int i = 0; i < 270; i++) {
			assertThat(redis.opsForZSet().score("feed:user:" + prefix + "-follower-" + i, post)).isEqualTo(1000.0);
		}
	}

	@Test
	void concurrentFollowAndCreateFromDifferentTopicsCannotMissDelivery() throws Exception {
		for (int i = 0; i < 15; i++) {
			setup();
			String create = postEvent("create", "POST_CREATED", 0);
			String follow = relation("follow", "USER_FOLLOWED");
			runConcurrently(() -> service.processRaw(create), () -> service.processRaw(follow));
			assertThat(redis.opsForZSet().score("feed:user:" + subscriber, post)).isEqualTo(1000.0);
		}
	}

	@Test
	void concurrentUserDeletionAndPostCreationCannotLeaveGlobalOrPersonalEntry() throws Exception {
		for (int i = 0; i < 15; i++) {
			setup();
			service.processRaw(relation("follow", "USER_FOLLOWED"));
			String create = postEvent("create", "POST_CREATED", 0);
			String deleted = event("deleted", "USER_DELETED", "{\"userId\":\"" + author + "\"}");
			runConcurrently(() -> service.processRaw(create), () -> service.processRaw(deleted));
			assertThat(redis.opsForZSet().score("feed:popular", post)).isNull();
			assertThat(redis.opsForZSet().score("feed:user:" + subscriber, post)).isNull();
		}
	}

	private void runConcurrently(Runnable first, Runnable second) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> {
				await(start);
				first.run();
			});
			var b = executor.submit(() -> {
				await(start);
				second.run();
			});
			start.countDown();
			a.get(20, TimeUnit.SECONDS);
			b.get(20, TimeUnit.SECONDS);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS))
				throw new IllegalStateException("Test start timed out");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
