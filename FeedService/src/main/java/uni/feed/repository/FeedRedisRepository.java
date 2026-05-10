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

	public List<String> findPopularPostIdsByCursor(long minLikesScore, int size) {
		Set<String> ids = redis.opsForZSet().reverseRangeByScore(popularFeedKey(), Double.NEGATIVE_INFINITY,
				(double) minLikesScore, 0, size);
		return ids == null ? List.of() : List.copyOf(ids);
	}

	public Map<String, Double> findFeedPostsWithScoresByCursor(String userId, long maxScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				userFeedKey(userId), Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findPopularPostsWithScoresByCursor(long maxLikesScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				popularFeedKey(), Double.NEGATIVE_INFINITY, (double) maxLikesScore, 0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findOutsideFeedPostsWithScoresByCursor(long maxScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				outsideFeedKey(), Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findOutsidePopularPostsWithScoresByCursor(long maxLikesScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				outsidePopularFeedKey(), Double.NEGATIVE_INFINITY, (double) maxLikesScore, 0, size);
		return tuplesToMap(tuples);
	}

	public long countPopular() {
		Long count = redis.opsForZSet().zCard(popularFeedKey());
		return count == null ? 0L : count;
	}

	public void addPostToAuthorFeed(String authorId, String postId, double score) {
		redis.opsForZSet().add(authorFeedKey(authorId), postId, score);
	}

	public void addPostToUserFeed(String userId, String postId, double score) {
		redis.opsForZSet().add(userFeedKey(userId), postId, score);
	}

	public void addPostToPopularFeed(String postId, double likesCount) {
		redis.opsForZSet().add(popularFeedKey(), postId, likesCount);
	}

	public void addPostToOutsideFeed(String postId, double score) {
		redis.opsForZSet().add(outsideFeedKey(), postId, score);
	}

	public void addPostToOutsidePopularFeed(String postId, double likesCount) {
		redis.opsForZSet().add(outsidePopularFeedKey(), postId, likesCount);
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

	public void removePostFromAuthorFeed(String authorId, String postId) {
		redis.opsForZSet().remove(authorFeedKey(authorId), postId);
	}

	public void removePostFromPopularFeed(String postId) {
		redis.opsForZSet().remove(popularFeedKey(), postId);
	}

	public void removePostFromOutsideFeed(String postId) {
		redis.opsForZSet().remove(outsideFeedKey(), postId);
	}

	public void removePostFromOutsidePopularFeed(String postId) {
		redis.opsForZSet().remove(outsidePopularFeedKey(), postId);
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

	public void trimPopularFeed(long maxSize) {
		trim(popularFeedKey(), maxSize);
	}

	public void trimOutsideFeed(long maxSize) {
		trim(outsideFeedKey(), maxSize);
	}

	public void trimOutsidePopularFeed(long maxSize) {
		trim(outsidePopularFeedKey(), maxSize);
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

	public Map<String, Double> findUniversityFacultyPostsWithScoresByCursor(long universityId, long facultyId,
			long maxScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				universityTopicFeedKey(universityId, facultyId), Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0,
				size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findUniversityFacultyPopularPostsWithScoresByCursor(long universityId, long facultyId,
			long maxScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				universityTopicPopularFeedKey(universityId, facultyId), Double.NEGATIVE_INFINITY, (double) maxScore, 0,
				size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findUniversityProgramPostsWithScoresByCursor(long universityId, long programId,
			long maxScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				universitySubtopicFeedKey(universityId, programId), Double.NEGATIVE_INFINITY, (double) (maxScore - 1),
				0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findUniversityProgramPopularPostsWithScoresByCursor(long universityId, long programId,
			long maxScore, int size) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(
				universitySubtopicPopularFeedKey(universityId, programId), Double.NEGATIVE_INFINITY, (double) maxScore,
				0, size);
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

	public Map<String, Double> findFollowingInUniversityFacultyWithScoresByCursor(String userId, long universityId,
			long facultyId, long maxScore, int size, Duration ttl) {
		String scopeKey = universityTopicFeedKey(universityId, facultyId);
		String tmpKey = "feed:tmp:following:" + userId + ":uni:" + universityId + ":faculty:" + facultyId;
		redis.opsForZSet().intersectAndStore(userFeedKey(userId), List.of(scopeKey), tmpKey, Aggregate.MAX);
		redis.expire(tmpKey, ttl);
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(tmpKey,
				Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findFollowingInUniversityProgramWithScoresByCursor(String userId, long universityId,
			long programId, long maxScore, int size, Duration ttl) {
		String scopeKey = universitySubtopicFeedKey(universityId, programId);
		String tmpKey = "feed:tmp:following:" + userId + ":uni:" + universityId + ":program:" + programId;
		redis.opsForZSet().intersectAndStore(userFeedKey(userId), List.of(scopeKey), tmpKey, Aggregate.MAX);
		redis.expire(tmpKey, ttl);
		Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(tmpKey,
				Double.NEGATIVE_INFINITY, (double) (maxScore - 1), 0, size);
		return tuplesToMap(tuples);
	}

	public Map<String, Double> findFollowingInOutsideWithScoresByCursor(String userId, long maxScore, int size,
			Duration ttl) {
		String tmpKey = "feed:tmp:following:" + userId + ":outside";
		redis.opsForZSet().intersectAndStore(userFeedKey(userId), List.of(outsideFeedKey()), tmpKey, Aggregate.MAX);
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
		redis.opsForSet().add(followingKey(subscriberId), targetUserId);
	}

	public void removeFollowingRelation(String subscriberId, String targetUserId) {
		redis.opsForSet().remove(followersKey(targetUserId), subscriberId);
		redis.opsForSet().remove(followingKey(subscriberId), targetUserId);
	}

	public void removeUserFromAllFeeds(String userId) {
		Map<String, Double> authoredPosts = findLatestAuthorPostsWithScores(userId, 0, -1);
		Set<String> followers = redis.opsForSet().members(followersKey(userId));
		if (followers != null) {
			for (String followerId : followers) {
				redis.opsForSet().remove(followingKey(followerId), userId);
				removePostsFromUserFeed(followerId, authoredPosts.keySet());
			}
		}
		Set<String> following = redis.opsForSet().members(followingKey(userId));
		if (following != null) {
			for (String targetUserId : following) {
				redis.opsForSet().remove(followersKey(targetUserId), userId);
			}
		}
		redis.delete(List.of(followingKey(userId), followersKey(userId), userFeedKey(userId), authorFeedKey(userId)));
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

	private static String userFeedKey(String userId) {
		return "feed:user:" + userId;
	}
	private static String followingKey(String userId) {
		return "feed:following:" + userId;
	}
	private static String authorFeedKey(String authorId) {
		return "feed:author:" + authorId;
	}
	private static String popularFeedKey() {
		return "feed:popular";
	}
	private static String outsideFeedKey() {
		return "feed:outside";
	}
	private static String outsidePopularFeedKey() {
		return "feed:outside:popular";
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
