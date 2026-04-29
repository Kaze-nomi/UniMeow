package uni.gateway.dto.post;

import lombok.Builder;

import java.util.List;

@Builder
public record PostDto(String id, String authorId, String content, List<String> mediaUrls, int likesCount,
		int commentsCount, boolean likedByMe, String createdAt, String updatedAt, Long universityId, Long facultyId,
		Long programId, Long topicId) {
	public PostDto {
		mediaUrls = mediaUrls == null ? List.of() : List.copyOf(mediaUrls);
	}
}
