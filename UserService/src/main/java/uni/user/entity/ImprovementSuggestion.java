package uni.user.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "improvement_suggestions")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ImprovementSuggestion {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private UUID authorId;

	@Column(nullable = false, length = 2000)
	private String text;

	@Column(nullable = false)
	private String status;

	@Column(nullable = false)
	private LocalDateTime createdAt;
}
