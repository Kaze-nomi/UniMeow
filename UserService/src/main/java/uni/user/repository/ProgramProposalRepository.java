package uni.user.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.ProgramProposal;

public interface ProgramProposalRepository extends JpaRepository<ProgramProposal, Long> {
	@Override
	@EntityGraph(attributePaths = { "university", "faculty" })
	List<ProgramProposal> findAll();
}
