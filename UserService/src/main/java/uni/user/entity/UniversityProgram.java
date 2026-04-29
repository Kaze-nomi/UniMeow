package uni.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "university_programs")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class UniversityProgram {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "university_id", nullable = false)
	private University university;

	@ManyToOne(optional = false)
	@JoinColumn(name = "faculty_id", nullable = false)
	private UniversityFaculty faculty;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String shortName;
}
