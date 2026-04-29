package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.FacultyProposal;

public interface FacultyProposalRepository extends JpaRepository<FacultyProposal, Long> {
}
