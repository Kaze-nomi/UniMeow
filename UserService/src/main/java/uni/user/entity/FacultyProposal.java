package uni.user.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "faculty_proposals")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class FacultyProposal {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private UUID authorId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "university_id", nullable = false)
	private University university;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String shortName;

	@Column(nullable = false)
	private String status;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	private UUID reviewedBy;
	private LocalDateTime reviewedAt;
}
