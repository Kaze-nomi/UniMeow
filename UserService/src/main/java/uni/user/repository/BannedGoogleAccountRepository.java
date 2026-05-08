package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.BannedGoogleAccount;

public interface BannedGoogleAccountRepository extends JpaRepository<BannedGoogleAccount, String> {
}
