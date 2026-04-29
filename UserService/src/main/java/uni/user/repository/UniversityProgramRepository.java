package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.UniversityProgram;

import java.util.List;

public interface UniversityProgramRepository extends JpaRepository<UniversityProgram, Long> {
	List<UniversityProgram> findByFacultyId(Long facultyId);
	boolean existsByFacultyIdAndShortName(Long facultyId, String shortName);
	java.util.Optional<UniversityProgram> findByFacultyIdAndShortNameIgnoreCase(Long facultyId, String shortName);
}
