package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.ImprovementSuggestion;

public interface ImprovementSuggestionRepository extends JpaRepository<ImprovementSuggestion, Long> {
}
