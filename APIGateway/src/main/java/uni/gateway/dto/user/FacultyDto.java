package uni.gateway.dto.user;

import lombok.Builder;

@Builder
public record FacultyDto(String id, String name, String shortName) {
}
