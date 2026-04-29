package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.ProgramProposal;

public interface ProgramProposalRepository extends JpaRepository<ProgramProposal, Long> {
}
