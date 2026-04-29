package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.EmailVerificationCode;

import java.util.UUID;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {
}
