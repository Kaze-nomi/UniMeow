package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.UniversityDomain;

import java.util.Optional;

public interface UniversityDomainRepository extends JpaRepository<UniversityDomain, Long> {
	Optional<UniversityDomain> findByDomain(String domain);
}
