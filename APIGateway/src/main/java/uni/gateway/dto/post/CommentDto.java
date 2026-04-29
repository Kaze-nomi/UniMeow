package uni.gateway.dto.post;

public record CommentDto(String id, String postId, String authorId, String content, boolean likedByMe,
		String likesCount, String createdAt, String updatedAt, String parentCommentId) {
}
