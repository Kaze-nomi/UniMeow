package uni.gateway.dto.post;

import java.util.List;

public record CommentPageDto(List<CommentDto> comments, int total) {
	public CommentPageDto {
		comments = comments == null ? List.of() : List.copyOf(comments);
	}
}
