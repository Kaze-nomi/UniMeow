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
@Table(name = "university_proposals")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class UniversityProposal {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private UUID authorId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String shortName;

	@Column(nullable = false)
	private String subdomain;

	@Column(nullable = false)
	private String studentDomain;

	@Column(nullable = false)
	private String employeeDomain;

	private String city;

	@Column(length = 2000)
	private String description;

	private String iconUrl;

	@Column(nullable = false)
	private String status;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	private UUID reviewedBy;
	private LocalDateTime reviewedAt;
}
