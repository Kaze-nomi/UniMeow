package uni.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "university_domains")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class UniversityDomain {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true, nullable = false)
	private String domain;

	@Column(nullable = false)
	@Enumerated(EnumType.STRING)
	private DomainRole role;

	@ManyToOne(optional = false)
	@JoinColumn(name = "university_id", nullable = false)
	private University university;

	public enum DomainRole {
		STUDENT, EMPLOYEE
	}
}
