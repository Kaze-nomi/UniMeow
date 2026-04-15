package uni.user.exception;

public class UnknownDomainException extends RuntimeException {
    public UnknownDomainException(String message) {
        super(message);
    }
}