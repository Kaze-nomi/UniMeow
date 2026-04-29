package uni.feed.record;

import java.util.List;

public record FeedPageResult(List<String> postIds, Long nextCursor, boolean hasMore) {
	public FeedPageResult {
		postIds = postIds == null ? List.of() : List.copyOf(postIds);
	}
}
