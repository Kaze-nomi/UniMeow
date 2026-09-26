package uni.outbox;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxStore {
	Optional<ShardClaim> claim(UUID owner, long leaseMillis);
	boolean renew(ShardClaim claim, long leaseMillis);
	List<OutboxRecord> pending(ShardClaim claim, int limit);
	boolean published(ShardClaim claim, List<OutboxRecord> records);
	void release(ShardClaim claim, long retryDelayMillis);
}
