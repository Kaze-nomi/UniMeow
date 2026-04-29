package uni.gateway.dto.post;

import java.util.List;

public record FeedPageDto(List<PostDto> posts, String nextCursor, boolean hasMore) {
	public FeedPageDto {
		posts = posts == null ? List.of() : List.copyOf(posts);
	}
}
