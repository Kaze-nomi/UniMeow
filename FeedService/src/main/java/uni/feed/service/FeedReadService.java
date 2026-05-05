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

	private static final long NO_UNIVERSITY_TOPIC_ID = -1L;
	private static final Duration FOLLOWING_INTERSECTION_TTL = Duration.ofSeconds(60);

	private final FeedRedisRepository feedRedisRepository;

	@Value("${app.feed.default-page-size:20}")
	private int defaultPageSize;

	@Value("${app.feed.max-page-size:100}")
	private int maxPageSize;

	public FeedPageResult getFeed(String feedType, String userId, long cursor, int size, long universityId,
			long facultyId, long programId, long topicId) {
		Long normalizedCursor = cursor > 0 ? cursor : null;
		Integer normalizedSize = size > 0 ? size : null;
		Long normalizedUniversityId = universityId > 0 ? universityId : null;
		Long normalizedFacultyId = facultyId > 0 ? facultyId : null;
		Long normalizedProgramId = programId > 0 ? programId : null;
		boolean outsideScope = topicId == NO_UNIVERSITY_TOPIC_ID;
		Long normalizedTopicId = topicId > 0 ? topicId : null;

		if (normalizedProgramId == null) {
			normalizedProgramId = normalizedTopicId;
		}

		return switch (feedType) {
			case "FOLLOWING" -> {
				if (outsideScope) {
					if (userId == null || userId.isBlank()) {
						throw new SecurityException("userId required for outside following feed");
					}
					yield getOutsideFollowingFeed(userId, normalizedCursor, normalizedSize);
				}
				if (normalizedUniversityId != null) {
					yield getFollowingFeed(userId, normalizedCursor, normalizedSize, normalizedUniversityId,
							normalizedFacultyId, normalizedProgramId);
				}
				if (userId == null || userId.isBlank()) {
					throw new SecurityException("userId required for FOLLOWING feed");
				}
				yield getFollowingFeed(userId, normalizedCursor, normalizedSize, null, null, null);
			}
			case "TRENDING" -> outsideScope
					? getOutsideTrendingFeed(normalizedCursor, normalizedSize)
					: getTrendingFeed(normalizedCursor, normalizedSize, normalizedUniversityId, normalizedFacultyId,
							normalizedProgramId);
			default -> throw new IllegalArgumentException("Unsupported feed type: " + feedType);
		};
	}

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

		boolean hasUserId = userId != null && !userId.isBlank();
		Map<String, Double> posts;
		if (!hasUniversityScope) {
			posts = feedRedisRepository.findFeedPostsWithScoresByCursor(userId, maxScore, s + 1);
		} else if (hasProgramScope) {
			posts = hasUserId
					? feedRedisRepository.findFollowingInUniversityProgramWithScoresByCursor(userId, universityId,
							programId, maxScore, s + 1, FOLLOWING_INTERSECTION_TTL)
					: feedRedisRepository.findUniversityProgramPostsWithScoresByCursor(universityId, programId,
							maxScore, s + 1);
		} else if (hasFacultyScope) {
			posts = hasUserId
					? feedRedisRepository.findFollowingInUniversityFacultyWithScoresByCursor(userId, universityId,
							facultyId, maxScore, s + 1, FOLLOWING_INTERSECTION_TTL)
					: feedRedisRepository.findUniversityFacultyPostsWithScoresByCursor(universityId, facultyId,
							maxScore, s + 1);
		} else {
			posts = hasUserId
					? feedRedisRepository.findFollowingInUniversityWithScoresByCursor(userId, universityId, maxScore,
							s + 1, FOLLOWING_INTERSECTION_TTL)
					: feedRedisRepository.findUniversityFeedPostsWithScoresByCursor(universityId, maxScore, s + 1);
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
			posts = feedRedisRepository.findUniversityProgramPopularPostsWithScoresByCursor(universityId, programId,
					maxScore, s + 1);
		} else if (hasFacultyScope) {
			posts = feedRedisRepository.findUniversityFacultyPopularPostsWithScoresByCursor(universityId, facultyId,
					maxScore, s + 1);
		} else {
			posts = feedRedisRepository.findUniversityPopularPostsWithScoresByCursor(universityId, maxScore, s + 1);
		}

		return buildPageResult(posts, s);
	}

	private FeedPageResult getOutsideFollowingFeed(String userId, Long cursor, Integer size) {
		int s = resolveSize(size);
		long maxScore = cursor != null ? cursor : Instant.now().toEpochMilli() + 1;
		Map<String, Double> posts = feedRedisRepository.findFollowingInOutsideWithScoresByCursor(userId, maxScore,
				s + 1, FOLLOWING_INTERSECTION_TTL);
		return buildPageResult(posts, s);
	}

	private FeedPageResult getOutsideTrendingFeed(Long cursor, Integer size) {
		int s = resolveSize(size);
		long maxScore = cursor != null ? cursor - 1 : Long.MAX_VALUE;
		Map<String, Double> posts = feedRedisRepository.findOutsidePopularPostsWithScoresByCursor(maxScore, s + 1);
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
