package uni.gateway.dto.admin;

public record BanUserInput(String targetUserId, String bannedUntil, String reason) {
}
