package uni.gateway.dto.post;

import java.util.List;

public record EditPostInput(String content, Boolean updateMediaUrls, List<String> mediaUrls) {
	public EditPostInput {
		mediaUrls = mediaUrls == null ? null : List.copyOf(mediaUrls);
	}
}
