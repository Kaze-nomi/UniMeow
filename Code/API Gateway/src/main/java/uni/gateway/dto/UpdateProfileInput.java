package uni.gateway.dto;

public record UpdateProfileInput(
        String username,
        String name,
        String surname,
        String patronymic,
        String status,
        String avatarUrl
) {}