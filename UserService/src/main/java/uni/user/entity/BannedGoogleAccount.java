package uni.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "banned_google_accounts")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class BannedGoogleAccount {

	@Id
	@Column(name = "email_google", nullable = false)
	private String emailGoogle;

	@Column(length = 500)
	private String reason;

	private UUID moderatorId;

	@Column(nullable = false)
	private LocalDateTime bannedAt;
}
