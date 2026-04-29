package uni.user.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "email_verification_codes")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class EmailVerificationCode {

	@Id
	private UUID userId;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String code;

	@Column(nullable = false)
	private int attempts;

	@Column(nullable = false)
	private LocalDateTime expiresAt;
}
