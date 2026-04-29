package uni.gateway.dto.user;

import java.util.List;

public record TopicDto(String id, String slug, String name, boolean isSystem, String facultyId,
		List<TopicDto> subtopics) {
	public TopicDto {
		subtopics = subtopics == null ? List.of() : List.copyOf(subtopics);
	}
}
