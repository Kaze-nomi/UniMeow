package uni.gateway.dto;

import lombok.Builder;

@Builder
public record UserDto(
        String id,
        String emailGoogle,
        String username,
        String name,
        String surname,
        String patronymic,
        String emailUniversity,
        String avatarUrl,
        String status,
        boolean isStudentVerified,
        boolean isEmployeeVerified,
        String createdAt
) {}