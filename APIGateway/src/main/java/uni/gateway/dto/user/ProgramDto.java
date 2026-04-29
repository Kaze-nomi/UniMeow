package uni.gateway.dto.user;

import lombok.Builder;

@Builder
public record ProgramDto(String id, String facultyId, String name, String shortName) {
}
