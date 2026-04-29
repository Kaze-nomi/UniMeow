package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.University;

import java.util.Optional;

public interface UniversityRepository extends JpaRepository<University, Long> {
	Optional<University> findByShortName(String shortName);
	Optional<University> findByName(String name);
	Optional<University> findBySubdomain(String subdomain);
}
