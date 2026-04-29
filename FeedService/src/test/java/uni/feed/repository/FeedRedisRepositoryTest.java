package uni.feed.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedRedisRepositoryTest {

	@Mock
	StringRedisTemplate redis;

	@Mock
	ZSetOperations<String, String> zSetOps;

	@Mock
	SetOperations<String, String> setOps;

	@Mock
	ValueOperations<String, String> valueOps;

	@InjectMocks
	FeedRedisRepository feedRedisRepository;

	@Test
	void countFeed_returns_count_for_user_feed() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:user:user-123")).thenReturn(42L);

		long result = feedRedisRepository.countFeed("user-123");

		assertThat(result).isEqualTo(42L);
		verify(zSetOps).zCard("feed:user:user-123");
	}

	@Test
	void countFeed_returns_count_for_global_feed_when_user_id_null() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:global")).thenReturn(100L);

		long result = feedRedisRepository.countFeed(null);

		assertThat(result).isEqualTo(100L);
		verify(zSetOps).zCard("feed:global");
	}

	@Test
	void countFeed_returns_zero_when_redis_returns_null() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:user:user-123")).thenReturn(null);

		long result = feedRedisRepository.countFeed("user-123");

		assertThat(result).isZero();
	}

	@Test
	void addPostToAuthorFeed_adds_post_with_score() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.addPostToAuthorFeed("author-123", "post-456", 1000.0);

		verify(zSetOps).add("feed:author:author-123", "post-456", 1000.0);
	}

	@Test
	void addPostToGlobalFeed_adds_post_with_score() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.addPostToGlobalFeed("post-456", 2000.0);

		verify(zSetOps).add("feed:global", "post-456", 2000.0);
	}

	@Test
	void addPostToUserFeed_adds_post_with_score() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.addPostToUserFeed("user-123", "post-456", 3000.0);

		verify(zSetOps).add("feed:user:user-123", "post-456", 3000.0);
	}

	@Test
	void removePostFromGlobalFeed_removes_post() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.removePostFromGlobalFeed("post-456");

		verify(zSetOps).remove("feed:global", "post-456");
	}

	@Test
	void removePostFromAuthorFeed_removes_post() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.removePostFromAuthorFeed("author-123", "post-456");

		verify(zSetOps).remove("feed:author:author-123", "post-456");
	}

	@Test
	void trimAuthorFeed_removes_excess_posts() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:author:author-123")).thenReturn(1500L);

		feedRedisRepository.trimAuthorFeed("author-123", 1000);

		verify(zSetOps).removeRange("feed:author:author-123", 0, 499);
	}

	@Test
	void trimAuthorFeed_does_nothing_when_feed_is_within_limit() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:author:author-123")).thenReturn(500L);

		feedRedisRepository.trimAuthorFeed("author-123", 1000);

		verify(zSetOps, never()).removeRange(any(), anyLong(), anyLong());
	}

	@Test
	void trimGlobalFeed_removes_excess_posts() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:global")).thenReturn(6000L);

		feedRedisRepository.trimGlobalFeed(5000);

		verify(zSetOps).removeRange("feed:global", 0, 999);
	}

	@Test
	void findFollowers_returns_set_of_followers() {
		when(redis.opsForSet()).thenReturn(setOps);
		when(setOps.members("feed:followers:author-123")).thenReturn(Set.of("follower-1", "follower-2"));

		Set<String> result = feedRedisRepository.findFollowers("author-123");

		assertThat(result).containsExactlyInAnyOrder("follower-1", "follower-2");
	}

	@Test
	void findFollowers_returns_empty_set_when_no_followers() {
		when(redis.opsForSet()).thenReturn(setOps);
		when(setOps.members("feed:followers:author-123")).thenReturn(null);

		Set<String> result = feedRedisRepository.findFollowers("author-123");

		assertThat(result).isEmpty();
	}

	@Test
	void addFollowingRelation_adds_to_followers_set() {
		when(redis.opsForSet()).thenReturn(setOps);

		feedRedisRepository.addFollowingRelation("subscriber-123", "target-456");

		verify(setOps).add("feed:followers:target-456", "subscriber-123");
	}

	@Test
	void removeFollowingRelation_removes_from_followers_set() {
		when(redis.opsForSet()).thenReturn(setOps);

		feedRedisRepository.removeFollowingRelation("subscriber-123", "target-456");

		verify(setOps).remove("feed:followers:target-456", "subscriber-123");
	}

	@Test
	void findLatestAuthorPostsWithScores_returns_posts_with_scores() {
		ZSetOperations.TypedTuple<String> tuple1 = mock();
		ZSetOperations.TypedTuple<String> tuple2 = mock();

		when(tuple1.getValue()).thenReturn("post-1");
		when(tuple1.getScore()).thenReturn(100.0);
		when(tuple2.getValue()).thenReturn("post-2");
		when(tuple2.getScore()).thenReturn(200.0);

		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.reverseRangeWithScores("feed:author:author-123", 0, 99)).thenReturn(Set.of(tuple1, tuple2));

		Map<String, Double> result = feedRedisRepository.findLatestAuthorPostsWithScores("author-123", 0, 99);

		assertThat(result).containsKeys("post-1", "post-2");
		assertThat(result).containsValues(100.0, 200.0);
	}

	@Test
	void findLatestAuthorPostsWithScores_returns_empty_map_when_no_posts() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.reverseRangeWithScores("feed:author:author-123", 0, 99)).thenReturn(null);

		Map<String, Double> result = feedRedisRepository.findLatestAuthorPostsWithScores("author-123", 0, 99);

		assertThat(result).isEmpty();
	}

	@Test
	void removePostsFromUserFeed_removes_all_posts() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		Collection<String> postIds = List.of("post-1", "post-2", "post-3");
		feedRedisRepository.removePostsFromUserFeed("user-123", postIds);

		ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
		verify(zSetOps).remove(eq("feed:user:user-123"), captor.capture());
		assertThat(captor.getValue()).containsExactlyInAnyOrder("post-1", "post-2", "post-3");
	}

	@Test
	void removePostsFromUserFeed_does_nothing_when_posts_is_empty() {
		feedRedisRepository.removePostsFromUserFeed("user-123", List.of());

		verifyNoInteractions(redis);
	}

	@Test
	void removePostsFromUserFeed_does_nothing_when_posts_is_null() {
		feedRedisRepository.removePostsFromUserFeed("user-123", null);

		verifyNoInteractions(redis);
	}

	@Test
	void isEventProcessed_returns_true_when_event_exists() {
		when(redis.hasKey("feed:processed:event:event-123")).thenReturn(true);

		boolean result = feedRedisRepository.isEventProcessed("event-123");

		assertThat(result).isTrue();
	}

	@Test
	void isEventProcessed_returns_false_when_event_not_exists() {
		when(redis.hasKey("feed:processed:event:event-123")).thenReturn(false);

		boolean result = feedRedisRepository.isEventProcessed("event-123");

		assertThat(result).isFalse();
	}

	@Test
	void markEventProcessed_sets_with_ttl() {
		when(redis.opsForValue()).thenReturn(valueOps);

		Duration ttl = Duration.ofDays(7);
		feedRedisRepository.markEventProcessed("event-123", ttl);

		verify(valueOps).set("feed:processed:event:event-123", "1", ttl);
	}

	@Test
	void addPostToPopularFeed_adds_with_likes_score() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.addPostToPopularFeed("post-1", 42.0);

		verify(zSetOps).add("feed:popular", "post-1", 42.0);
	}

	@Test
	void removePostFromPopularFeed_removes_post() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.removePostFromPopularFeed("post-1");

		verify(zSetOps).remove("feed:popular", "post-1");
	}

	@Test
	void trimPopularFeed_removes_excess_posts() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:popular")).thenReturn(1500L);

		feedRedisRepository.trimPopularFeed(1000);

		verify(zSetOps).removeRange("feed:popular", 0, 499);
	}

	@Test
	void trimPopularFeed_does_nothing_when_within_limit() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:popular")).thenReturn(500L);

		feedRedisRepository.trimPopularFeed(1000);

		verify(zSetOps, never()).removeRange(any(), anyLong(), anyLong());
	}

	@Test
	void findFeedPostIdsByCursor_queries_user_feed_with_score_range() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.reverseRangeByScore("feed:user:user-123", Double.NEGATIVE_INFINITY, (double) (1700000000000L - 1),
				0, 20)).thenReturn(Set.of("post1", "post2"));

		List<String> result = feedRedisRepository.findFeedPostIdsByCursor("user-123", 1700000000000L, 20);

		assertThat(result).containsAll(List.of("post1", "post2"));
	}

	@Test
	void findFeedPostIdsByCursor_uses_global_feed_for_null_user() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.reverseRangeByScore(eq("feed:global"), anyDouble(), anyDouble(), eq(0L), eq(20L)))
				.thenReturn(Set.of("post1"));

		List<String> result = feedRedisRepository.findFeedPostIdsByCursor(null, 1700000000000L, 20);

		assertThat(result).contains("post1");
		verify(zSetOps).reverseRangeByScore(eq("feed:global"), anyDouble(), anyDouble(), anyLong(), anyLong());
	}

	@Test
	void findFeedPostIdsByCursor_returns_empty_list_when_redis_returns_null() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.reverseRangeByScore(any(), anyDouble(), anyDouble(), anyLong(), anyLong())).thenReturn(null);

		List<String> result = feedRedisRepository.findFeedPostIdsByCursor("user-123", 1700000000000L, 20);

		assertThat(result).isEmpty();
	}

	@Test
	void countPopular_returns_count() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:popular")).thenReturn(55L);

		long result = feedRedisRepository.countPopular();

		assertThat(result).isEqualTo(55L);
	}

	@Test
	void countPopular_returns_zero_when_redis_returns_null() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(zSetOps.zCard("feed:popular")).thenReturn(null);

		long result = feedRedisRepository.countPopular();

		assertThat(result).isZero();
	}

	@Test
	void addPostToUniversityTopicFeed_uses_topic_key() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.addPostToUniversityTopicFeed(7L, 5L, "post-1", 123.0);

		verify(zSetOps).add("feed:uni:7:topic:5", "post-1", 123.0);
	}

	@Test
	void addPostToUniversitySubtopicPopularFeed_uses_subtopic_popular_key() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.addPostToUniversitySubtopicPopularFeed(7L, 11L, "post-1", 9.0);

		verify(zSetOps).add("feed:uni:7:subtopic:11:popular", "post-1", 9.0);
	}

	@Test
	void findFollowingInUniversityWithScoresByCursor_intersects_user_and_uni_feeds() {
		when(redis.opsForZSet()).thenReturn(zSetOps);

		feedRedisRepository.findFollowingInUniversityWithScoresByCursor("u1", 7L, 500L, 10, Duration.ofSeconds(60));

		verify(zSetOps).intersectAndStore("feed:user:u1", List.of("feed:uni:7"), "feed:tmp:following:u1:uni:7",
				Aggregate.MAX);
		verify(redis).expire("feed:tmp:following:u1:uni:7", Duration.ofSeconds(60));
	}

	@Test
	void findUniversityTopicPostsWithScoresByCursor_prefers_subtopic_key_when_present() {
		when(redis.opsForZSet()).thenReturn(zSetOps);
		when(redis.hasKey("feed:uni:7:subtopic:11")).thenReturn(true);

		feedRedisRepository.findUniversityTopicPostsWithScoresByCursor(7L, 11L, 500L, 10);

		verify(zSetOps).reverseRangeByScoreWithScores(eq("feed:uni:7:subtopic:11"), anyDouble(), anyDouble(), eq(0L),
				eq(10L));
	}
}
