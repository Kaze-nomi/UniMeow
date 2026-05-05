package uni.feed.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import uni.feed.record.FeedPageResult;
import uni.feed.repository.FeedRedisRepository;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedReadServiceTest {

	@Mock
	FeedRedisRepository repo;

	@InjectMocks
	FeedReadService service;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(service, "defaultPageSize", 20);
		ReflectionTestUtils.setField(service, "maxPageSize", 100);
	}

	@Test
	void followingFeed_returns_posts_in_descending_score_order() {
		when(repo.findFeedPostsWithScoresByCursor(eq("u1"), anyLong(), anyInt()))
				.thenReturn(lm("p1", 3000.0, "p2", 2000.0, "p3", 1000.0));

		FeedPageResult r = service.getFollowingFeed("u1", null, 20);

		assertThat(r.postIds()).containsExactly("p1", "p2", "p3");
	}

	@Test
	void followingFeed_empty_when_no_subscriptions() {
		when(repo.findFeedPostsWithScoresByCursor(eq("u1"), anyLong(), anyInt())).thenReturn(Map.of());

		FeedPageResult r = service.getFollowingFeed("u1", null, 20);

		assertThat(r.postIds()).isEmpty();
		assertThat(r.hasMore()).isFalse();
	}

	@Test
	void followingFeed_hasMore_true_and_cursor_set_when_overflow() {
		var posts = new LinkedHashMap<String, Double>();
		for (int i = 0; i <= 20; i++)
			posts.put("p" + i, (double) (5000 - i * 100));

		when(repo.findFeedPostsWithScoresByCursor(eq("u1"), anyLong(), anyInt())).thenReturn(posts);

		FeedPageResult r = service.getFollowingFeed("u1", null, 20);

		assertThat(r.hasMore()).isTrue();
		assertThat(r.postIds()).hasSize(20);
		assertThat(r.nextCursor()).isEqualTo(3100L);
	}

	@Test
	void followingFeed_hasMore_false_when_all_fit() {
		when(repo.findFeedPostsWithScoresByCursor(eq("u1"), anyLong(), anyInt()))
				.thenReturn(lm("p1", 2000.0, "p2", 1000.0));

		FeedPageResult r = service.getFollowingFeed("u1", null, 20);

		assertThat(r.hasMore()).isFalse();
		assertThat(r.nextCursor()).isNull();
	}

	@Test
	void followingFeed_passes_cursor_to_repo() {
		long cursor = 1_700_000_000_000L;
		when(repo.findFeedPostsWithScoresByCursor(eq("u1"), eq(cursor), anyInt())).thenReturn(Map.of());

		service.getFollowingFeed("u1", cursor, 20);

		verify(repo).findFeedPostsWithScoresByCursor(eq("u1"), eq(cursor), anyInt());
	}

	@Test
	void followingFeed_uses_user_feed_key_not_global() {
		when(repo.findFeedPostsWithScoresByCursor(eq("u1"), anyLong(), anyInt())).thenReturn(Map.of());

		service.getFollowingFeed("u1", null, 20);

		verify(repo, never()).findFeedPostsWithScoresByCursor(isNull(), anyLong(), anyInt());
	}

	@Test
	void trendingFeed_returns_posts_by_likes_descending() {
		when(repo.findPopularPostsWithScoresByCursor(anyLong(), anyInt()))
				.thenReturn(lm("viral", 500.0, "popular", 100.0, "mild", 10.0));

		FeedPageResult r = service.getTrendingFeed(null, 20);

		assertThat(r.postIds()).containsExactly("viral", "popular", "mild");
	}

	@Test
	void trendingFeed_initial_cursor_is_max_value() {
		when(repo.findPopularPostsWithScoresByCursor(eq(Long.MAX_VALUE), anyInt())).thenReturn(Map.of());

		service.getTrendingFeed(null, 20);

		verify(repo).findPopularPostsWithScoresByCursor(eq(Long.MAX_VALUE), anyInt());
	}

	@Test
	void trendingFeed_cursor_is_exclusive_upper_bound() {
		when(repo.findPopularPostsWithScoresByCursor(eq(99L), anyInt())).thenReturn(Map.of());

		service.getTrendingFeed(100L, 20);

		verify(repo).findPopularPostsWithScoresByCursor(eq(99L), anyInt());
	}

	@Test
	void trendingFeed_hasMore_and_cursor_on_overflow() {
		var posts = new LinkedHashMap<String, Double>();
		for (int i = 0; i <= 20; i++)
			posts.put("p" + i, (double) (1000 - i));

		when(repo.findPopularPostsWithScoresByCursor(anyLong(), anyInt())).thenReturn(posts);

		FeedPageResult r = service.getTrendingFeed(null, 20);

		assertThat(r.hasMore()).isTrue();
		assertThat(r.postIds()).hasSize(20);
		assertThat(r.nextCursor()).isEqualTo(981L);
	}

	@Test
	void trendingFeed_does_not_use_feed_user_or_global_keys() {
		when(repo.findPopularPostsWithScoresByCursor(anyLong(), anyInt())).thenReturn(Map.of());

		service.getTrendingFeed(null, 20);

		verify(repo, never()).findFeedPostsWithScoresByCursor(any(), anyLong(), anyInt());
	}

	@Test
	void trendingFeed_hasMore_false_when_all_fit() {
		when(repo.findPopularPostsWithScoresByCursor(anyLong(), anyInt())).thenReturn(lm("p1", 10.0, "p2", 5.0));

		FeedPageResult r = service.getTrendingFeed(null, 20);

		assertThat(r.hasMore()).isFalse();
		assertThat(r.nextCursor()).isNull();
	}

	@Test
	void caps_size_at_max_page_size() {
		when(repo.findPopularPostsWithScoresByCursor(anyLong(), anyInt())).thenReturn(Map.of());

		service.getTrendingFeed(null, 500);

		verify(repo).findPopularPostsWithScoresByCursor(anyLong(), intThat(n -> n <= 101));
	}

	@Test
	void uses_default_size_when_null() {
		when(repo.findPopularPostsWithScoresByCursor(anyLong(), anyInt())).thenReturn(Map.of());

		service.getTrendingFeed(null, null);

		verify(repo).findPopularPostsWithScoresByCursor(anyLong(), eq(21));
	}

	@Test
	void uses_default_size_when_zero() {
		when(repo.findPopularPostsWithScoresByCursor(anyLong(), anyInt())).thenReturn(Map.of());

		service.getTrendingFeed(null, 0);

		verify(repo).findPopularPostsWithScoresByCursor(anyLong(), eq(21));
	}

	@Test
	void followingFeed_caps_size_at_max_page_size() {
		when(repo.findFeedPostsWithScoresByCursor(eq("u1"), anyLong(), anyInt())).thenReturn(Map.of());

		service.getFollowingFeed("u1", null, 999);

		verify(repo).findFeedPostsWithScoresByCursor(eq("u1"), anyLong(), intThat(n -> n <= 101));
	}

	@Test
	void trendingFeed_uses_default_size_when_negative() {
		when(repo.findPopularPostsWithScoresByCursor(anyLong(), anyInt())).thenReturn(Map.of());

		service.getTrendingFeed(null, -5);

		verify(repo).findPopularPostsWithScoresByCursor(anyLong(), eq(21));
	}

	@Test
	void trendingFeed_uses_university_program_popular_scope() {
		when(repo.findUniversityProgramPopularPostsWithScoresByCursor(eq(7L), eq(3L), anyLong(), anyInt()))
				.thenReturn(Map.of());

		service.getTrendingFeed(null, 20, 7L, 3L);

		verify(repo).findUniversityProgramPopularPostsWithScoresByCursor(eq(7L), eq(3L), anyLong(), anyInt());
		verify(repo, never()).findPopularPostsWithScoresByCursor(anyLong(), anyInt());
	}

	@Test
	void getFeed_uses_outside_popular_scope_when_no_university_topic_requested() {
		when(repo.findOutsidePopularPostsWithScoresByCursor(anyLong(), anyInt())).thenReturn(Map.of());

		service.getFeed("TRENDING", null, 0, 20, 0, 0, 0, -1);

		verify(repo).findOutsidePopularPostsWithScoresByCursor(eq(Long.MAX_VALUE), anyInt());
		verify(repo, never()).findPopularPostsWithScoresByCursor(anyLong(), anyInt());
	}

	@Test
	void getFeed_rejects_unsupported_feed_type() {
		assertThatThrownBy(() -> service.getFeed("UNKNOWN", null, 0, 20, 0, 0, 0, 0))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported feed type");
	}

	@Test
	void followingFeed_uses_intersection_for_university_scope() {
		when(repo.findFollowingInUniversityWithScoresByCursor(eq("u1"), eq(7L), anyLong(), anyInt(), any()))
				.thenReturn(Map.of());

		service.getFollowingFeed("u1", null, 20, 7L, null);

		verify(repo).findFollowingInUniversityWithScoresByCursor(eq("u1"), eq(7L), anyLong(), anyInt(), any());
		verify(repo, never()).findFeedPostsWithScoresByCursor(eq("u1"), anyLong(), anyInt());
	}

	@Test
	void followingFeed_uses_intersection_for_university_program_scope() {
		when(repo.findFollowingInUniversityProgramWithScoresByCursor(eq("u1"), eq(7L), eq(3L), anyLong(), anyInt(),
				any())).thenReturn(Map.of());

		service.getFollowingFeed("u1", null, 20, 7L, 3L);

		verify(repo).findFollowingInUniversityProgramWithScoresByCursor(eq("u1"), eq(7L), eq(3L), anyLong(), anyInt(),
				any());
	}

	private static Map<String, Double> lm(Object... kv) {
		var m = new LinkedHashMap<String, Double>();
		for (int i = 0; i < kv.length; i += 2)
			m.put((String) kv[i], (Double) kv[i + 1]);
		return m;
	}
}
