package uni.post.record;

import java.util.List;

public record CommentPageResult(List<CommentResult> comments, long total) {
	public CommentPageResult {
		comments = comments == null ? List.of() : List.copyOf(comments);
	}
}
