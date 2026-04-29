package uni.feed.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Repository
public class FeedRedisRepository {

	private final StringRedisTemplate redis;

	public FeedRedisRepository(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public List<String> findFeedPostIdsByCursor(String userId, long maxScore, int size) {
		String key = userId != null && !userId.isBlank() ? userFeedKey(userId) : globalFeedKey();
		Set<String> ids = redis.opsForZSet().reverseRangeByScore(key, Double.NEGATIVE_INFINITY, (double) (maxScore - 1),
				0, size);
		return ids == null ? List.of() : List.copyOf(ids);
	}

	public List<String> findPopularPostIdsByCursor(long minLikesScore, int size) {
		Set<String> ids = redis.opsForZSet().reverseRangeByScore(popularFeedKey(), Double.NEGATIVE_INFINITY,
				(double) minLikesScore, 0, size);
		return ids == null ? List.of() : List.copyOf(ids);
	}

	public Map<String, Double> findFeedPostsWithScoresByCursor(String userId, long maxScore, int size) {
		String key = userId != null && !userId.isBlank() ? userFeedKey(userId) : globalFeedKey();
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(key,
				Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findPopularPostsWithScoresByCursor(long maxLikesScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				popularFeedKey(), Double.NEGATIVE_INFINITY, (double) maxLikesScore, 0, size);
		return tuplesToMap(tuples);
	}

	public long countFeed(String userId) {
		String key = userId != null && !userId.isBlank() ? userFeedKey(userId) : globalFeedKey();
		Long count = redis.opsForZSet().zCard(key);
		return count == null ? 0L : count;
	}

	public long countPopular() {
		Long count = redis.opsForZSet().zCard(popularFeedKey());
		return count == null ? 0L : count;
	}

	public void addPostToAuthorFeed(String authorId, String postId, double score) {
		redis.opsForZSet().add(authorFeedKey(authorId), postId, score);
	}

	public void addPostToGlobalFeed(String postId, double score) {
		redis.opsForZSet().add(globalFeedKey(), postId, score);
	}

	public void addPostToUserFeed(String userId, String postId, double score) {
		redis.opsForZSet().add(userFeedKey(userId), postId, score);
	}

	public void addPostToPopularFeed(String postId, double likesCount) {
		redis.opsForZSet().add(popularFeedKey(), postId, likesCount);
	}

	public void addPostToUniversityFeed(long universityId, String postId, double score) {
		redis.opsForZSet().add(universityFeedKey(universityId), postId, score);
	}

	public void addPostToUniversityTopicFeed(long universityId, long topicId, String postId, double score) {
		redis.opsForZSet().add(universityTopicFeedKey(universityId, topicId), postId, score);
	}

	public void addPostToUniversitySubtopicFeed(long universityId, long subtopicId, String postId, double score) {
		redis.opsForZSet().add(universitySubtopicFeedKey(universityId, subtopicId), postId, score);
	}

	public void addPostToUniversityPopularFeed(long universityId, String postId, double likesCount) {
		redis.opsForZSet().add(universityPopularFeedKey(universityId), postId, likesCount);
	}

	public void addPostToUniversityTopicPopularFeed(long universityId, long topicId, String postId, double likesCount) {
		redis.opsForZSet().add(universityTopicPopularFeedKey(universityId, topicId), postId, likesCount);
	}

	public void addPostToUniversitySubtopicPopularFeed(long universityId, long subtopicId, String postId,
			double likesCount) {
		redis.opsForZSet().add(universitySubtopicPopularFeedKey(universityId, subtopicId), postId, likesCount);
	}

	public void incrementPopularScore(String postId, double delta) {
		redis.opsForZSet().incrementScore(popularFeedKey(), postId, delta);
	}

	public void removePostFromGlobalFeed(String postId) {
		redis.opsForZSet().remove(globalFeedKey(), postId);
	}

	public void removePostFromAuthorFeed(String authorId, String postId) {
		redis.opsForZSet().remove(authorFeedKey(authorId), postId);
	}

	public void removePostFromPopularFeed(String postId) {
		redis.opsForZSet().remove(popularFeedKey(), postId);
	}

	public void removePostFromUniversityFeed(long universityId, String postId) {
		redis.opsForZSet().remove(universityFeedKey(universityId), postId);
	}

	public void removePostFromUniversityTopicFeed(long universityId, long topicId, String postId) {
		redis.opsForZSet().remove(universityTopicFeedKey(universityId, topicId), postId);
	}

	public void removePostFromUniversitySubtopicFeed(long universityId, long subtopicId, String postId) {
		redis.opsForZSet().remove(universitySubtopicFeedKey(universityId, subtopicId), postId);
	}

	public void removePostFromUniversityPopularFeed(long universityId, String postId) {
		redis.opsForZSet().remove(universityPopularFeedKey(universityId), postId);
	}

	public void removePostFromUniversityTopicPopularFeed(long universityId, long topicId, String postId) {
		redis.opsForZSet().remove(universityTopicPopularFeedKey(universityId, topicId), postId);
	}

	public void removePostFromUniversitySubtopicPopularFeed(long universityId, long subtopicId, String postId) {
		redis.opsForZSet().remove(universitySubtopicPopularFeedKey(universityId, subtopicId), postId);
	}

	public void trimAuthorFeed(String authorId, long maxSize) {
		trim(authorFeedKey(authorId), maxSize);
	}

	public void trimGlobalFeed(long maxSize) {
		trim(globalFeedKey(), maxSize);
	}

	public void trimPopularFeed(long maxSize) {
		trim(popularFeedKey(), maxSize);
	}

	public void trimUniversityFeed(long universityId, long maxSize) {
		trim(universityFeedKey(universityId), maxSize);
	}

	public void trimUniversityTopicFeed(long universityId, long topicId, long maxSize) {
		trim(universityTopicFeedKey(universityId, topicId), maxSize);
	}

	public void trimUniversitySubtopicFeed(long universityId, long subtopicId, long maxSize) {
		trim(universitySubtopicFeedKey(universityId, subtopicId), maxSize);
	}

	public void trimUniversityPopularFeed(long universityId, long maxSize) {
		trim(universityPopularFeedKey(universityId), maxSize);
	}

	public void trimUniversityTopicPopularFeed(long universityId, long topicId, long maxSize) {
		trim(universityTopicPopularFeedKey(universityId, topicId), maxSize);
	}

	public void trimUniversitySubtopicPopularFeed(long universityId, long subtopicId, long maxSize) {
		trim(universitySubtopicPopularFeedKey(universityId, subtopicId), maxSize);
	}

	public Map<String, Double> findUniversityFeedPostsWithScoresByCursor(long universityId, long maxScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				universityFeedKey(universityId), Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findUniversityPopularPostsWithScoresByCursor(long universityId, long maxScore,
			int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				universityPopularFeedKey(universityId), Double.NEGATIVE_INFINITY, (double) maxScore, 0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findUniversityTopicPostsWithScoresByCursor(long universityId, long topicId,
			long maxScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				resolveTopicScopedFeedKey(universityId, topicId), Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0,
				size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findUniversityTopicPopularPostsWithScoresByCursor(long universityId, long topicId,
			long maxScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				resolveTopicScopedPopularFeedKey(universityId, topicId), Double.NEGATIVE_INFINITY, (double) maxScore, 0,
				size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findFollowingInUniversityWithScoresByCursor(String userId, long universityId,
			long maxScore, int size, Duration ttl) {
		String tmpKey = "feed:tmp:following:" + userId + ":uni:" + universityId;
		redis.opsForZSet().intersectAndStore(userFeedKey(userId), List.of(universityFeedKey(universityId)), tmpKey,
				Aggregate.MAX);
		redis.expire(tmpKey, ttl);
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(tmpKey,
				Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findFollowingInUniversityTopicWithScoresByCursor(String userId, long universityId,
			long topicId, long maxScore, int size, Duration ttl) {
		String scopeKey = resolveTopicScopedFeedKey(universityId, topicId);
		String tmpKey = "feed:tmp:following:" + userId + ":uni:" + universityId + ":topic:" + topicId;
		redis.opsForZSet().intersectAndStore(userFeedKey(userId), List.of(scopeKey), tmpKey, Aggregate.MAX);
		redis.expire(tmpKey, ttl);
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(tmpKey,
				Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0, size);
		return tuplesToMap(tuples);
	}

	public Set<String> findFollowers(String authorId) {
		Set<String> followers = redis.opsForSet().members(followersKey(authorId));
		return followers == null ? Set.of() : followers;
	}

	public void addFollowingRelation(String subscriberId, String targetUserId) {
		redis.opsForSet().add(followersKey(targetUserId), subscriberId);
	}

	public void removeFollowingRelation(String subscriberId, String targetUserId) {
		redis.opsForSet().remove(followersKey(targetUserId), subscriberId);
	}

	public Map<String, Double> findLatestAuthorPostsWithScores(String authorId, long from, long to) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
				.reverseRangeWithScores(authorFeedKey(authorId), from, to);
		return tuplesToMap(tuples);
	}

	public void removePostsFromUserFeed(String userId, Collection<String> postIds) {
		if (postIds == null || postIds.isEmpty())
			return;
		redis.opsForZSet().remove(userFeedKey(userId), postIds.toArray(new Object[0]));
	}

	public boolean isEventProcessed(String eventId) {
		return Boolean.TRUE.equals(redis.hasKey(processedEventKey(eventId)));
	}

	public void markEventProcessed(String eventId, Duration ttl) {
		redis.opsForValue().set(processedEventKey(eventId), "1", ttl);
	}

	private void trim(String key, long maxSize) {
		Long size = redis.opsForZSet().zCard(key);
		if (size != null && size > maxSize) {
			redis.opsForZSet().removeRange(key, 0, size - maxSize - 1);
		}
	}

	private static Map<String, Double> tuplesToMap(Set<ZSetOperations.TypedTuple<String>> tuples) {
		if (tuples == null || tuples.isEmpty())
			return Map.of();
		Map<String, Double> result = new LinkedHashMap<>();
		for (ZSetOperations.TypedTuple<String> t : tuples) {
			if (t == null || t.getValue() == null || t.getScore() == null)
				continue;
			result.put(t.getValue(), t.getScore());
		}
		return result;
	}

	private String resolveTopicScopedFeedKey(long universityId, long topicId) {
		String subtopicKey = universitySubtopicFeedKey(universityId, topicId);
		Boolean hasSubtopic = redis.hasKey(subtopicKey);
		return Boolean.TRUE.equals(hasSubtopic) ? subtopicKey : universityTopicFeedKey(universityId, topicId);
	}

	private String resolveTopicScopedPopularFeedKey(long universityId, long topicId) {
		String subtopicKey = universitySubtopicPopularFeedKey(universityId, topicId);
		Boolean hasSubtopic = redis.hasKey(subtopicKey);
		return Boolean.TRUE.equals(hasSubtopic) ? subtopicKey : universityTopicPopularFeedKey(universityId, topicId);
	}

	private static String userFeedKey(String userId) {
		return "feed:user:" + userId;
	}
	private static String globalFeedKey() {
		return "feed:global";
	}
	private static String authorFeedKey(String authorId) {
		return "feed:author:" + authorId;
	}
	private static String popularFeedKey() {
		return "feed:popular";
	}
	private static String universityFeedKey(long universityId) {
		return "feed:uni:" + universityId;
	}
	private static String universityPopularFeedKey(long universityId) {
		return "feed:uni:" + universityId + ":popular";
	}
	private static String universityTopicFeedKey(long universityId, long topicId) {
		return "feed:uni:" + universityId + ":topic:" + topicId;
	}
	private static String universityTopicPopularFeedKey(long universityId, long topicId) {
		return "feed:uni:" + universityId + ":topic:" + topicId + ":popular";
	}
	private static String universitySubtopicFeedKey(long universityId, long topicId) {
		return "feed:uni:" + universityId + ":subtopic:" + topicId;
	}
	private static String universitySubtopicPopularFeedKey(long universityId, long topicId) {
		return "feed:uni:" + universityId + ":subtopic:" + topicId + ":popular";
	}
	private static String followersKey(String userId) {
		return "feed:followers:" + userId;
	}
	private static String processedEventKey(String id) {
		return "feed:processed:event:" + id;
	}
}
