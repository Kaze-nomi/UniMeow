package uni.gateway.dto.user;

public record VerificationResult(boolean success, String message) {
	public VerificationResult(boolean success) {
		this(success, null);
	}
}
