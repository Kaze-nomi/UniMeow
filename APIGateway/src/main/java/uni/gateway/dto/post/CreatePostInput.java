package uni.gateway.dto.post;

import java.util.List;

public record CreatePostInput(String content, List<String> mediaUrls, Long topicId) {
	public CreatePostInput {
		mediaUrls = mediaUrls == null ? null : List.copyOf(mediaUrls);
	}
}
