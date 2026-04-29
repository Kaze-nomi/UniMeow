package uni.post.record;

import java.util.List;

public record PostPageResult(List<PostResult> posts, long total) {
	public PostPageResult {
		posts = posts == null ? List.of() : List.copyOf(posts);
	}
}
