package uni.gateway.dto.user;

import lombok.Builder;

@Builder
public record UniversityDto(String id, String name, String shortName, String subdomain, String iconUrl) {
}
