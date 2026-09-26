package uni.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/**
 * Short DB transactions only. PostgreSQL clock and a fresh token govern every
 * claim.
 */
public final class JdbcOutboxStore implements OutboxStore {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcOutboxStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
		this.jdbc.setQueryTimeout(5);
		this.transactions = new TransactionTemplate(transactionManager);
		this.transactions.setTimeout(5);
	}

	public double pendingCount() {
		Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
		return count == null ? 0 : count.doubleValue();
	}

	public double oldestPendingSeconds() {
		Double age = jdbc.queryForObject("""
				SELECT greatest(0, coalesce(extract(epoch FROM
				    ((clock_timestamp() AT TIME ZONE 'UTC') - min(created_at))), 0))
				FROM outbox_events WHERE published_at IS NULL
				""", Double.class);
		return age == null ? 0 : age;
	}

	@Override
	public Optional<ShardClaim> claim(UUID owner, long leaseMillis) {
		UUID token = UUID.randomUUID();
		return jdbc.query("""
				WITH candidate AS (
				    SELECT s.shard_id FROM outbox_shard_owners s
				    WHERE s.lease_until <= clock_timestamp() AND s.retry_after <= clock_timestamp()
				      AND EXISTS (SELECT 1 FROM outbox_events e WHERE e.published_at IS NULL
				          AND outbox_shard(e.topic, e.event_key) = s.shard_id)
				    ORDER BY s.last_claimed_at, s.shard_id
				    FOR UPDATE SKIP LOCKED LIMIT 1
				)
				UPDATE outbox_shard_owners s SET owner_id = ?, claim_token = ?,
				    lease_until = clock_timestamp() + (? * interval '1 millisecond'),
				    last_claimed_at = clock_timestamp()
				FROM candidate c WHERE s.shard_id = c.shard_id RETURNING s.shard_id
				""", (row, index) -> new ShardClaim(row.getInt(1), owner, token), owner, token, leaseMillis).stream()
				.findFirst();
	}

	@Override
	public boolean renew(ShardClaim claim, long leaseMillis) {
		return jdbc.update("""
				UPDATE outbox_shard_owners SET lease_until = clock_timestamp() + (? * interval '1 millisecond')
				WHERE shard_id = ? AND owner_id = ? AND claim_token = ? AND lease_until > clock_timestamp()
				""", leaseMillis, claim.shard(), claim.owner(), claim.token()) == 1;
	}

	@Override
	public List<OutboxRecord> pending(ShardClaim claim, int limit) {
		return jdbc.query(
				"""
						SELECT e.id, e.topic, e.event_key, e.payload::text FROM outbox_events e
						WHERE e.published_at IS NULL AND outbox_shard(e.topic, e.event_key) = ?
						  AND EXISTS (SELECT 1 FROM outbox_shard_owners s WHERE s.shard_id = ?
						    AND s.owner_id = ? AND s.claim_token = ? AND s.lease_until > clock_timestamp())
						ORDER BY e.created_at, e.id LIMIT ?
						""", (row, index) -> new OutboxRecord(row.getObject(1, UUID.class), row.getString(2),
						row.getString(3), row.getString(4)),
				claim.shard(), claim.shard(), claim.owner(), claim.token(), limit);
	}

	@Override
	public boolean published(ShardClaim claim, List<OutboxRecord> records) {
		return Boolean.TRUE.equals(transactions.execute(status -> {
			// Lock before checking ownership; a concurrent takeover must wait for this
			// short checkpoint.
			List<Integer> valid = jdbc.query("""
					SELECT shard_id FROM outbox_shard_owners WHERE shard_id = ? AND owner_id = ?
					  AND claim_token = ? AND lease_until > clock_timestamp() FOR UPDATE
					""", (row, index) -> row.getInt(1), claim.shard(), claim.owner(), claim.token());
			if (valid.isEmpty()) {
				return false;
			}
			for (OutboxRecord record : records) {
				jdbc.update("""
						UPDATE outbox_events SET published_at = clock_timestamp() AT TIME ZONE 'UTC'
						WHERE id = ? AND published_at IS NULL AND outbox_shard(topic, event_key) = ?
						""", record.id(), claim.shard());
			}
			return true;
		}));
	}

	@Override
	public void release(ShardClaim claim, long retryDelayMillis) {
		jdbc.update("""
				UPDATE outbox_shard_owners SET owner_id = NULL, claim_token = NULL,
				    lease_until = '-infinity', retry_after = clock_timestamp() + (? * interval '1 millisecond')
				WHERE shard_id = ? AND owner_id = ? AND claim_token = ?
				""", retryDelayMillis, claim.shard(), claim.owner(), claim.token());
	}
}
