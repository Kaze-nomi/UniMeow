package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.UniversityFaculty;

import java.util.List;

public interface UniversityFacultyRepository extends JpaRepository<UniversityFaculty, Long> {
	List<UniversityFaculty> findByUniversityId(Long universityId);
	boolean existsByUniversityIdAndShortName(Long universityId, String shortName);
}
