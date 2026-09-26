package uni.feed.repository;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Durable receipts and restartable Redis projections. Each script has a fixed
 * number of operations; fanout is scanned outside Lua and can restart after any
 * failure. Metadata is retained with the Redis volume, without a replay TTL.
 * This implementation deliberately targets the standalone Redis used by
 * Compose.
 */
@Repository
public class FeedProjectionRepository {

	private final StringRedisTemplate redis;

	public FeedProjectionRepository(StringRedisTemplate redis) {
		this.redis = redis;
	}

	private static final DefaultRedisScript<Long> BEGIN = script("""
			if redis.call('HGET', KEYS[1], 'done') == '1' then return -1 end
			if redis.call('EXISTS', KEYS[3]) == 1 then
			  redis.call('HSET', KEYS[1], 'done', '1')
			  return -1
			end
			local revision = redis.call('HGET', KEYS[1], 'revision')
			if revision then return tonumber(revision) end
			revision = redis.call('INCR', KEYS[2])
			redis.call('HSET', KEYS[1], 'revision', revision)
			return revision
			""");

	// The revision belongs to the first delivery of an ID, including an unfinished
	// delivery. It is not a reason to skip other independent effects of an event.
	public long begin(String eventId) {
		return execute(BEGIN, List.of(receipt(eventId), "feed:projection:revision", "feed:processed:event:" + eventId));
	}

	public void complete(String eventId) {
		redis.opsForHash().put(receipt(eventId), "done", "1");
	}

	public record Destination(String key, long limit, boolean popular) {
		public Destination {
			if (limit < 1 || limit > 10000) {
				throw new IllegalArgumentException("Feed windows must be between 1 and 10000");
			}
		}
	}

	private static final DefaultRedisScript<Long> PROJECT = script("""
			if redis.call('HGET', KEYS[1], 'deleted') == '1' then return 0 end
			local author = ARGV[2]
			if author == '' then author = redis.call('HGET', KEYS[1], 'author') or '' end
			if redis.call('EXISTS', 'feed:deleted:user:' .. author) == 1 then return 0 end
			redis.call('HSET', KEYS[1], 'author', author)
			if author ~= '' then redis.call('SADD', 'feed:posts:author:' .. author, ARGV[1]) end
			local revision = tonumber(ARGV[3])
			local previous = tonumber(redis.call('HGET', KEYS[1], 'popularRevision') or '-1')
			if previous < 0 or (ARGV[6] ~= '1' and revision >= previous) then
			  redis.call('HSET', KEYS[1], 'popularRevision', revision, 'popularScore', ARGV[5])
			end
			local popular = redis.call('HGET', KEYS[1], 'popularScore')
			for i = 3, #KEYS do
			  local arg = 7 + (i - 3) * 2
			  local score = ARGV[4]
			  if ARGV[arg + 1] == '1' then score = popular end
			  redis.call('ZADD', KEYS[i], score, ARGV[1])
			  redis.call('SADD', KEYS[2], KEYS[i])
			  local extra = redis.call('ZCARD', KEYS[i]) - tonumber(ARGV[arg])
			  if extra > 0 then redis.call('ZREMRANGEBYRANK', KEYS[i], 0, extra - 1) end
			end
			return 1
			""");

	public void projectPost(String postId, String authorId, long revision, double createdScore, double popularScore,
			boolean initializePopularity, List<Destination> destinations) {
		if (destinations.size() > 10) {
			throw new IllegalArgumentException("Too many fixed post projections");
		}
		List<String> keys = new ArrayList<>(List.of(post(postId), destinations(postId)));
		List<String> args = new ArrayList<>(List.of(postId, authorId == null ? "" : authorId, Long.toString(revision),
				Double.toString(createdScore), Double.toString(popularScore), initializePopularity ? "1" : "0"));
		for (Destination destination : destinations) {
			keys.add(destination.key());
			args.add(Long.toString(destination.limit()));
			args.add(destination.popular() ? "1" : "0");
		}
		execute(PROJECT, keys, args.toArray());
	}

	private static final DefaultRedisScript<Long> DELIVER = script("""
			if redis.call('HGET', KEYS[1], 'deleted') == '1'
			  or redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[3]) == 1
			  or redis.call('SISMEMBER', KEYS[4], ARGV[2]) == 0 then return 0 end
			redis.call('ZADD', KEYS[5], ARGV[3], ARGV[1])
			redis.call('ZADD', KEYS[6], ARGV[3], ARGV[1])
			redis.call('SADD', KEYS[7], ARGV[2])
			for i = 5, 6 do
			  local extra = redis.call('ZCARD', KEYS[i]) - tonumber(ARGV[4])
			  if extra > 0 then redis.call('ZREMRANGEBYRANK', KEYS[i], 0, extra - 1) end
			end
			return 1
			""");

	public void deliver(String author, String subscriber, String postId, double score, long limit) {
		execute(DELIVER,
				List.of(post(postId), deletedUser(author), deletedUser(subscriber), followers(author),
						userFeed(subscriber), delivered(subscriber, author), recipients(postId)),
				postId, subscriber, Double.toString(score), Long.toString(limit));
	}

	public void fanout(String author, String postId, double score, long limit) {
		scanSet(followers(author), subscriber -> deliver(author, subscriber, postId, score, limit));
	}

	private static final DefaultRedisScript<Long> RELATION = script("""
			local previous = tonumber(redis.call('HGET', KEYS[1], 'revision') or '-1')
			local deleted = redis.call('EXISTS', KEYS[4]) == 1 or redis.call('EXISTS', KEYS[5]) == 1
			if not deleted and tonumber(ARGV[3]) < previous then return 0 end
			redis.call('HSET', KEYS[1], 'revision', ARGV[3])
			if ARGV[4] == '1' and not deleted then
			  redis.call('SADD', KEYS[2], ARGV[1])
			  redis.call('SADD', KEYS[3], ARGV[2])
			else
			  redis.call('SREM', KEYS[2], ARGV[1])
			  redis.call('SREM', KEYS[3], ARGV[2])
			end
			return 1
			""");

	public void follow(String subscriber, String author, long revision, long limit) {
		if (changeRelation(subscriber, author, revision, true) == 0) {
			return;
		}
		Set<ZSetOperations.TypedTuple<String>> posts = redis.opsForZSet().reverseRangeWithScores(authorFeed(author), 0,
				99);
		if (posts != null) {
			for (var item : posts) {
				deliver(author, subscriber, item.getValue(), item.getScore(), limit);
			}
		}
	}

	private static final DefaultRedisScript<Long> WITHDRAW = script("""
			if redis.call('SISMEMBER', KEYS[1], ARGV[2]) == 1 then return 0 end
			redis.call('ZREM', KEYS[2], ARGV[1])
			redis.call('ZREM', KEYS[3], ARGV[1])
			redis.call('SREM', KEYS[4], ARGV[2])
			return 1
			""");

	public void unfollow(String subscriber, String author, long revision) {
		if (changeRelation(subscriber, author, revision, false) == 0) {
			return;
		}
		Consumer<String> remove = id -> execute(WITHDRAW,
				List.of(followers(author), userFeed(subscriber), delivered(subscriber, author), recipients(id)), id,
				subscriber);
		scanSorted(delivered(subscriber, author), remove);
		// Existing installations have no delivery index until the new projection runs.
		scanSorted(authorFeed(author), remove);
	}

	private long changeRelation(String subscriber, String author, long revision, boolean follow) {
		return execute(RELATION,
				List.of("feed:relation:" + subscriber + ":" + author, followers(author), following(subscriber),
						deletedUser(author), deletedUser(subscriber)),
				subscriber, author, Long.toString(revision), follow ? "1" : "0");
	}

	private static final DefaultRedisScript<Long> DELETE_POST = script("""
			redis.call('HSET', KEYS[1], 'deleted', '1')
			for i = 2, #KEYS do redis.call('ZREM', KEYS[i], ARGV[1]) end
			return 1
			""");

	private static final DefaultRedisScript<Long> DELETE_DELIVERY = script("""
			redis.call('ZREM', KEYS[1], ARGV[1])
			redis.call('ZREM', KEYS[2], ARGV[1])
			redis.call('SREM', KEYS[3], ARGV[2])
			return 1
			""");

	public void deletePost(String postId, String author, List<String> legacyDestinations) {
		// Tombstone is durable before any resumable cleanup and blocks all future adds.
		execute(DELETE_POST, List.of(post(postId)), postId);
		scanSet(destinations(postId), key -> execute(DELETE_POST, List.of(post(postId), key), postId));
		for (String key : legacyDestinations) {
			execute(DELETE_POST, List.of(post(postId), key), postId);
		}
		String knownAuthor = author != null ? author : (String) redis.opsForHash().get(post(postId), "author");
		Consumer<String> remove = subscriber -> execute(DELETE_DELIVERY,
				List.of(userFeed(subscriber), delivered(subscriber, knownAuthor), recipients(postId)), postId,
				subscriber);
		scanSet(recipients(postId), remove);
		if (knownAuthor != null) {
			scanSet(followers(knownAuthor), remove);
			redis.opsForZSet().remove(authorFeed(knownAuthor), postId);
		}
	}

	public void deleteUser(String userId, long revision) {
		redis.opsForValue().set(deletedUser(userId), "1");
		scanSet("feed:posts:author:" + userId, id -> deletePost(id, userId, List.of()));
		scanSorted(authorFeed(userId), id -> deletePost(id, userId,
				List.of(authorFeed(userId), "feed:popular", "feed:outside", "feed:outside:popular")));
		scanSet(followers(userId), subscriber -> unfollow(subscriber, userId, revision));
		scanSet(following(userId), author -> unfollow(userId, author, revision));
		redis.delete(List.of(userFeed(userId), authorFeed(userId), followers(userId), following(userId)));
	}

	private void scanSet(String key, Consumer<String> action) {
		try (Cursor<String> cursor = redis.opsForSet().scan(key, ScanOptions.scanOptions().count(128).build())) {
			cursor.forEachRemaining(action);
		}
	}

	private void scanSorted(String key, Consumer<String> action) {
		try (Cursor<ZSetOperations.TypedTuple<String>> cursor = redis.opsForZSet().scan(key,
				ScanOptions.scanOptions().count(128).build())) {
			cursor.forEachRemaining(tuple -> action.accept(tuple.getValue()));
		}
	}

	private long execute(DefaultRedisScript<Long> script, List<String> keys, Object... args) {
		Long result = redis.execute(script, keys, args);
		if (result == null) {
			throw new IllegalStateException("Redis did not return a projection result");
		}
		return result;
	}

	private static DefaultRedisScript<Long> script(String source) {
		return new DefaultRedisScript<>(source, Long.class);
	}

	private static String receipt(String id) {
		return "feed:receipt:" + id;
	}
	private static String post(String id) {
		return "feed:post:" + id;
	}
	private static String destinations(String id) {
		return "feed:post:destinations:" + id;
	}
	private static String recipients(String id) {
		return "feed:post:recipients:" + id;
	}
	private static String delivered(String subscriber, String author) {
		return "feed:delivery:" + subscriber + ":" + author;
	}
	private static String deletedUser(String id) {
		return "feed:deleted:user:" + id;
	}
	private static String followers(String id) {
		return "feed:followers:" + id;
	}
	private static String following(String id) {
		return "feed:following:" + id;
	}
	private static String authorFeed(String id) {
		return "feed:author:" + id;
	}
	private static String userFeed(String id) {
		return "feed:user:" + id;
	}
}
