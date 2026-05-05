package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.UniversityTopic;

import java.util.Optional;

public interface UniversityTopicRepository extends JpaRepository<UniversityTopic, Long> {
	Optional<UniversityTopic> findByUniversityIdAndSlugAndParentIsNull(Long universityId, String slug);
}
