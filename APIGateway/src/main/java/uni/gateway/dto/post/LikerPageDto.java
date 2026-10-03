package uni.gateway.dto.post;

import java.util.List;

public record LikerPageDto(List<LikerDto> users, int total) {
}
