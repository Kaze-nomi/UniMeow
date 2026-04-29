package uni.gateway.dto.post;

import java.util.List;

public record PostPageDto(List<PostDto> posts, int total) {
	public PostPageDto {
		posts = posts == null ? List.of() : List.copyOf(posts);
	}
}
