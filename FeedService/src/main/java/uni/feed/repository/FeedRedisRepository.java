package uni.feed.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.stereotype.Repository;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import uni.feed.events.EventEnvelope;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Repository
public class FeedRedisRepository {

	private static final DefaultRedisScript<Long> APPLY_POST = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[2], KEYS[3]) > 0 then return 0 end
			local allowed = true
			if ARGV[4] ~= 'remove' and redis.call('EXISTS', KEYS[4], KEYS[5], KEYS[6]) > 0 then
			    allowed = false
			end
			if ARGV[5] ~= '' and ARGV[7] ~= 'any' then
			    local following = redis.call('SISMEMBER', KEYS[7], ARGV[5]) == 1
			    if (ARGV[4] == 'remove' and following) or (ARGV[4] ~= 'remove' and not following) then
			        allowed = false
			    end
			end
			if allowed then
			    if ARGV[4] == 'remove' then
			        redis.call('ZREM', KEYS[1], ARGV[1])
			    elseif ARGV[4] == 'create' then
			        redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
			    else
			        redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
			    end
			    if tonumber(ARGV[6]) > 0 then
			        redis.call('ZREMRANGEBYRANK', KEYS[1], 0, -tonumber(ARGV[6]) - 1)
			    end
			end
			redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
			return 1
			""", Long.class);

	private static final DefaultRedisScript<Long> APPLY_RELATION = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[1], KEYS[2]) > 0 then return 0 end
			if ARGV[3] == 'remove' then
			    redis.call('SREM', KEYS[3], ARGV[1])
			    redis.call('SREM', KEYS[4], ARGV[2])
			elseif redis.call('EXISTS', KEYS[5], KEYS[6]) == 0 then
			    redis.call('SADD', KEYS[3], ARGV[1])
			    redis.call('SADD', KEYS[4], ARGV[2])
			end
			redis.call('SET', KEYS[1], '1', 'EX', ARGV[4])
			return 1
			""", Long.class);

	private final StringRedisTemplate redis;

	public FeedRedisRepository(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public void applyPostProjection(EventEnvelope event, String feedKey, String postId, String authorId,
			String followerId, double score, long maxSize, Duration ttl) {
		String action = switch (event.eventType()) {
			case "POST_DELETED", "USER_UNFOLLOWED" -> "remove";
			case "POST_CREATED", "USER_FOLLOWED" -> "create";
			default -> "update";
		};
		String receipt = "feed:receipt:" + event.eventId() + ":" + feedKey + ":" + postId;
		Long result = redis.execute(APPLY_POST, List.of(feedKey, receipt, processedEventKey(event.eventId()),
				deletedPostKey(postId), deletedUserKey(authorId), deletedUserKey(followerId),
				followersKey(authorId)), postId, Double.toString(score), Long.toString(ttl.toSeconds()), action,
				followerId == null ? "" : followerId, Long.toString(maxSize),
				event.eventType().equals("POST_DELETED") ? "any" : "relation");
		if (result == null) throw new IllegalStateException("Feed projection was not applied");
	}

	public void applyFollowingEvent(EventEnvelope event, String subscriberId, String targetUserId, Duration ttl) {
		Long result = redis.execute(APPLY_RELATION, List.of("feed:receipt:" + event.eventId() + ":relation",
				processedEventKey(event.eventId()), followersKey(targetUserId), followingKey(subscriberId),
				deletedUserKey(subscriberId), deletedUserKey(targetUserId)), subscriberId, targetUserId,
				event.eventType().equals("USER_UNFOLLOWED") ? "remove" : "add", Long.toString(ttl.toSeconds()));
		if (result == null) throw new IllegalStateException("Following relation was not applied");
	}

	public void markPostDeleted(String postId, Duration ttl) {
		redis.opsForValue().set(deletedPostKey(postId), "1", ttl);
	}

	public void markUserDeleted(String userId, Duration ttl) {
		redis.opsForValue().set(deletedUserKey(userId), "1", ttl);
	}

	private static String deletedPostKey(String postId) {
		return "feed:deleted:post:" + postId;
	}

	private static String deletedUserKey(String userId) {
		return "feed:deleted:user:" + (userId == null ? "" : userId);
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

	public void trimUserFeed(String userId, long maxSize) {
		trim(userFeedKey(userId), maxSize);
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
