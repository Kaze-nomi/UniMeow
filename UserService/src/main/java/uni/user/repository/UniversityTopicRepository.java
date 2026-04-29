package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.UniversityTopic;

import java.util.List;
import java.util.Optional;

public interface UniversityTopicRepository extends JpaRepository<UniversityTopic, Long> {
	List<UniversityTopic> findByUniversityId(Long universityId);
	List<UniversityTopic> findByUniversityIdAndParentIsNull(Long universityId);
	List<UniversityTopic> findByParentId(Long parentId);
	Optional<UniversityTopic> findByUniversityIdAndSlugAndParentIsNull(Long universityId, String slug);
	Optional<UniversityTopic> findByUniversityIdAndSlug(Long universityId, String slug);
}
