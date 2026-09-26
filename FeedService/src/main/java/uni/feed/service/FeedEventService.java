package uni.feed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uni.feed.events.EventEnvelope;
import uni.feed.repository.FeedProjectionRepository;
import uni.feed.repository.FeedProjectionRepository.Destination;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class FeedEventService {

	private final ObjectMapper objectMapper;
	private final FeedProjectionRepository projections;

	@Value("${app.feed.author-window-size:1000}")
	private long authorWindowSize;
	@Value("${app.feed.user-window-size:1000}")
	private long userWindowSize;
	@Value("${app.feed.uni-window-size:5000}")
	private long uniWindowSize;
	@Value("${app.feed.trending-window-size:5000}")
	private long popularWindowSize;
	@Value("${app.feed.trending-like-boost-ms:3600000}")
	private long trendingLikeBoostMs;

	public void processRaw(String raw) {
		EventEnvelope event = parse(raw);
		if (event.eventId() == null || event.eventId().isBlank()) {
			throw new IllegalArgumentException("eventId is missing");
		}
		if (event.eventType() == null) {
			throw new IllegalArgumentException("eventType is missing");
		}
		long revision = projections.begin(event.eventId());
		if (revision < 0) {
			return;
		}
		switch (event.eventType()) {
			case "POST_CREATED", "POST_LIKED", "POST_UNLIKED" -> projectPost(event, revision);
			case "POST_DELETED" -> {
				String post = required(event, "postId");
				String author = text(event.payload(), "authorId");
				projections.deletePost(post, author, destinations(event, true).stream().map(Destination::key).toList());
			}
			case "USER_FOLLOWED", "USER_UNFOLLOWED" -> {
				String subscriber = required(event, "subscriberId");
				String author = required(event, "targetUserId");
				if (Objects.equals(subscriber, author)) {
					throw new IllegalArgumentException(event.eventType() + " payload is invalid");
				}
				if (event.eventType().equals("USER_FOLLOWED")) {
					projections.follow(subscriber, author, revision, userWindowSize);
				} else {
					projections.unfollow(subscriber, author, revision);
				}
			}
			case "USER_DELETED", "USER_PERMANENT_BANNED" -> {
				String user = text(event.payload(), "userId");
				if (user == null) {
					user = event.aggregateId();
				}
				if (user == null || user.isBlank()) {
					throw new IllegalArgumentException(event.eventType() + " payload is invalid");
				}
				projections.deleteUser(user, revision);
			}
			default -> {
			}
		}
		// Receipt is completed only after every resumable effect has returned.
		projections.complete(event.eventId());
	}

	private void projectPost(EventEnvelope event, long revision) {
		String post = required(event, "postId");
		boolean created = event.eventType().equals("POST_CREATED");
		String author = created ? required(event, "authorId") : text(event.payload(), "authorId");
		String createdAtMs = text(event.payload(), "createdAtMs");
		double createdScore = createdAtMs == null ? score(event.occurredAt()) : number(createdAtMs);
		String count = text(event.payload(), "likesCount");
		long likes = count == null ? 0 : number(count);
		double popularScore = createdScore + likes * (double) trendingLikeBoostMs;
		projections.projectPost(post, author, revision, createdScore, popularScore, created,
				destinations(event, created));
		if (created) {
			projections.fanout(author, post, createdScore, userWindowSize);
		}
	}

	private List<Destination> destinations(EventEnvelope event, boolean chronological) {
		List<Destination> result = new ArrayList<>();
		String author = text(event.payload(), "authorId");
		if (chronological && author != null) {
			result.add(new Destination("feed:author:" + author, authorWindowSize, false));
		}
		result.add(new Destination("feed:popular", popularWindowSize, true));
		Long university = nullableNumber(text(event.payload(), "authorUniversityId"));
		if (university == null) {
			university = nullableNumber(text(event.payload(), "universityId"));
		}
		if (university == null) {
			addScope(result, "feed:outside", chronological);
		} else {
			String base = "feed:uni:" + university;
			addScope(result, base, chronological);
			Long parent = nullableNumber(text(event.payload(), "parentTopicId"));
			Long faculty = nullableNumber(text(event.payload(), "facultyId"));
			if (faculty == null) {
				faculty = parent;
			}
			if (faculty != null) {
				addScope(result, base + ":topic:" + faculty, chronological);
			}
			Long program = nullableNumber(text(event.payload(), "programId"));
			Long topic = nullableNumber(text(event.payload(), "topicId"));
			if (program == null && parent != null && topic != null && !topic.equals(parent)) {
				program = topic;
			}
			if (program != null) {
				addScope(result, base + ":subtopic:" + program, chronological);
			}
		}
		return result;
	}

	private void addScope(List<Destination> destinations, String key, boolean chronological) {
		if (chronological) {
			destinations.add(new Destination(key, uniWindowSize, false));
		}
		destinations.add(new Destination(key + ":popular", popularWindowSize, true));
	}

	private EventEnvelope parse(String raw) {
		try {
			EventEnvelope event = objectMapper.readValue(raw, EventEnvelope.class);
			if (event == null) {
				throw new IllegalArgumentException("Event must be a JSON object");
			}
			return event;
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to deserialize event", e);
		}
	}

	private static String required(EventEnvelope event, String field) {
		String value = text(event.payload(), field);
		if (value == null) {
			throw new IllegalArgumentException(event.eventType() + " payload is incomplete: " + field);
		}
		return value;
	}

	private static String text(JsonNode node, String field) {
		if (node == null || node.get(field) == null || node.get(field).isNull()) {
			return null;
		}
		String value = node.get(field).asText();
		return value == null || value.isBlank() ? null : value;
	}

	private static double score(String occurredAt) {
		try {
			return Instant.parse(occurredAt).toEpochMilli();
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid occurredAt", e);
		}
	}

	private static long number(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid event number", e);
		}
	}

	private static Long nullableNumber(String value) {
		return value == null ? null : number(value);
	}
}
