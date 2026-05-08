package uni.user.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.FacultyProposal;

public interface FacultyProposalRepository extends JpaRepository<FacultyProposal, Long> {
	@Override
	@EntityGraph(attributePaths = "university")
	List<FacultyProposal> findAll();
}
