package uni.feed.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uni.feed.record.FeedPageResult;
import uni.feed.repository.FeedRedisRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FeedReadService {

	private static final Duration FOLLOWING_INTERSECTION_TTL = Duration.ofSeconds(60);

	private final FeedRedisRepository feedRedisRepository;

	@Value("${app.feed.default-page-size:20}")
	private int defaultPageSize;

	@Value("${app.feed.max-page-size:100}")
	private int maxPageSize;

	public FeedPageResult getFollowingFeed(String userId, Long cursor, Integer size) {
		return getFollowingFeed(userId, cursor, size, null, null);
	}

	public FeedPageResult getFollowingFeed(String userId, Long cursor, Integer size, Long universityId, Long topicId) {
		return getFollowingFeed(userId, cursor, size, universityId, null, topicId);
	}

	public FeedPageResult getFollowingFeed(String userId, Long cursor, Integer size, Long universityId, Long facultyId,
			Long programId) {
		int s = resolveSize(size);
		long maxScore = cursor != null ? cursor : Instant.now().toEpochMilli() + 1;
		boolean hasUniversityScope = universityId != null && universityId > 0;
		boolean hasProgramScope = programId != null && programId > 0;
		boolean hasFacultyScope = facultyId != null && facultyId > 0;

		Map<String, Double> posts;
		if (!hasUniversityScope) {
			posts = feedRedisRepository.findFeedPostsWithScoresByCursor(userId, maxScore, s + 1);
		} else if (hasProgramScope) {
			posts = feedRedisRepository.findFollowingInUniversityTopicWithScoresByCursor(userId, universityId,
					programId, maxScore, s + 1, FOLLOWING_INTERSECTION_TTL);
		} else if (hasFacultyScope) {
			posts = feedRedisRepository.findFollowingInUniversityTopicWithScoresByCursor(userId, universityId,
					facultyId, maxScore, s + 1, FOLLOWING_INTERSECTION_TTL);
		} else {
			posts = feedRedisRepository.findFollowingInUniversityWithScoresByCursor(userId, universityId, maxScore,
					s + 1, FOLLOWING_INTERSECTION_TTL);
		}

		return buildPageResult(posts, s);
	}

	public FeedPageResult getTrendingFeed(Long cursor, Integer size) {
		return getTrendingFeed(cursor, size, null, null);
	}

	public FeedPageResult getTrendingFeed(Long cursor, Integer size, Long universityId, Long topicId) {
		return getTrendingFeed(cursor, size, universityId, null, topicId);
	}

	public FeedPageResult getTrendingFeed(Long cursor, Integer size, Long universityId, Long facultyId,
			Long programId) {
		int s = resolveSize(size);
		long maxScore = cursor != null ? cursor - 1 : Long.MAX_VALUE;
		boolean hasUniversityScope = universityId != null && universityId > 0;
		boolean hasProgramScope = programId != null && programId > 0;
		boolean hasFacultyScope = facultyId != null && facultyId > 0;

		Map<String, Double> posts;
		if (!hasUniversityScope) {
			posts = feedRedisRepository.findPopularPostsWithScoresByCursor(maxScore, s + 1);
		} else if (hasProgramScope) {
			posts = feedRedisRepository.findUniversityTopicPopularPostsWithScoresByCursor(universityId, programId,
					maxScore, s + 1);
		} else if (hasFacultyScope) {
			posts = feedRedisRepository.findUniversityTopicPopularPostsWithScoresByCursor(universityId, facultyId,
					maxScore, s + 1);
		} else {
			posts = feedRedisRepository.findUniversityPopularPostsWithScoresByCursor(universityId, maxScore, s + 1);
		}

		return buildPageResult(posts, s);
	}

	public FeedPageResult getGlobalFeed(Long cursor, Integer size) {
		return getGlobalFeed(cursor, size, null, null);
	}

	public FeedPageResult getGlobalFeed(Long cursor, Integer size, Long universityId, Long topicId) {
		return getGlobalFeed(cursor, size, universityId, null, topicId);
	}

	public FeedPageResult getGlobalFeed(Long cursor, Integer size, Long universityId, Long facultyId, Long programId) {
		int s = resolveSize(size);
		long maxScore = cursor != null ? cursor : Instant.now().toEpochMilli() + 1;
		boolean hasUniversityScope = universityId != null && universityId > 0;
		boolean hasProgramScope = programId != null && programId > 0;
		boolean hasFacultyScope = facultyId != null && facultyId > 0;

		Map<String, Double> posts;
		if (!hasUniversityScope) {
			posts = feedRedisRepository.findFeedPostsWithScoresByCursor(null, maxScore, s + 1);
		} else if (hasProgramScope) {
			posts = feedRedisRepository.findUniversityTopicPostsWithScoresByCursor(universityId, programId, maxScore,
					s + 1);
		} else if (hasFacultyScope) {
			posts = feedRedisRepository.findUniversityTopicPostsWithScoresByCursor(universityId, facultyId, maxScore,
					s + 1);
		} else {
			posts = feedRedisRepository.findUniversityFeedPostsWithScoresByCursor(universityId, maxScore, s + 1);
		}

		return buildPageResult(posts, s);
	}

	private FeedPageResult buildPageResult(Map<String, Double> posts, int size) {
		if (posts.isEmpty()) {
			return new FeedPageResult(List.of(), null, false);
		}

		boolean hasMore = posts.size() > size;
		List<String> ids = new ArrayList<>(posts.keySet());
		if (hasMore) {
			ids = ids.subList(0, size);
		}

		Long nextCursor = null;
		if (hasMore) {
			Double lastScore = posts.get(ids.get(ids.size() - 1));
			nextCursor = lastScore != null ? lastScore.longValue() : null;
		}

		return new FeedPageResult(List.copyOf(ids), nextCursor, hasMore);
	}

	private int resolveSize(Integer size) {
		int s = size != null && size > 0 ? size : defaultPageSize;
		return Math.min(s, maxPageSize);
	}
}
