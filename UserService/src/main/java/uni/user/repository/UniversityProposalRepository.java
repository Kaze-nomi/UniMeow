package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.UniversityProposal;

public interface UniversityProposalRepository extends JpaRepository<UniversityProposal, Long> {
}
