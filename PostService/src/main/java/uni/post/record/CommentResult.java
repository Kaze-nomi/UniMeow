package uni.post.record;

import uni.post.entity.Comment;

public record CommentResult(Comment comment, boolean likedByMe) {
}
