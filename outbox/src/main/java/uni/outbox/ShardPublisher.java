package uni.outbox;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Each claim initializes exactly once; a fenced producer never recreates itself
 * using an old token.
 */
public final class ShardPublisher implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(ShardPublisher.class);
	private final UUID instanceId = UUID.randomUUID();
	private final OutboxStore store;
	private final IntFunction<Producer<String, String>> producerFactory;
	private final int workers;
	private final int batchSize;
	private final long leaseMillis;
	private final long operationTimeoutMillis;
	private final long transactionTimeoutMillis;
	private final ExecutorService executor;
	private final AtomicInteger running = new AtomicInteger();
	private final AtomicBoolean stopping = new AtomicBoolean();

	public ShardPublisher(OutboxStore store, IntFunction<Producer<String, String>> producerFactory, int workers,
			int batchSize, long leaseMillis, long operationTimeoutMillis, long transactionTimeoutMillis) {
		if (workers < 1 || workers > 16 || batchSize < 1 || batchSize > 100 || operationTimeoutMillis < 1000
				|| transactionTimeoutMillis <= operationTimeoutMillis
				|| leaseMillis <= transactionTimeoutMillis + operationTimeoutMillis) {
			throw new IllegalArgumentException("Invalid bounded outbox worker, batch or timeout settings");
		}
		this.store = store;
		this.producerFactory = producerFactory;
		this.workers = workers;
		this.batchSize = batchSize;
		this.leaseMillis = leaseMillis;
		this.operationTimeoutMillis = operationTimeoutMillis;
		this.transactionTimeoutMillis = transactionTimeoutMillis;
		this.executor = Executors.newFixedThreadPool(workers, Thread.ofPlatform().name("outbox-", 0).factory());
	}

	public synchronized void publishPendingEvents() {
		if (stopping.get()) {
			return;
		}
		int available = workers - running.get();
		for (int slot = 0; slot < available; slot++) {
			running.incrementAndGet();
			executor.submit(() -> {
				try {
					publishOneBatch();
				} catch (Exception error) {
					log.error("Outbox claim failed instanceId={}", instanceId, error);
				} finally {
					running.decrementAndGet();
				}
			});
		}
	}

	public void publishOneBatch() {
		if (stopping.get()) {
			return;
		}
		store.claim(instanceId, leaseMillis).ifPresent(this::publishClaim);
	}

	private void publishClaim(ShardClaim claim) {
		Producer<String, String> producer = null;
		boolean success = false;
		boolean transactionStarted = false;
		boolean commitAttempted = false;
		try {
			requireOwnership(claim);
			producer = producerFactory.apply(claim.shard());
			// May fence a newer owner if this thread was paused. The following DB check
			// prevents stale work.
			// The newer owner stops on fencing and takes a fresh token; neither side loops
			// initTransactions.
			producer.initTransactions();
			requireOwnership(claim);
			List<OutboxRecord> records = store.pending(claim, batchSize);
			if (records.isEmpty()) {
				success = true;
				return;
			}
			producer.beginTransaction();
			transactionStarted = true;
			long deadline = System.nanoTime()
					+ TimeUnit.MILLISECONDS.toNanos(transactionTimeoutMillis - operationTimeoutMillis);
			for (OutboxRecord record : records) {
				requireOwnership(claim);
				if (System.nanoTime() >= deadline) {
					throw new IllegalStateException("Outbox transaction budget exhausted");
				}
				producer.send(new ProducerRecord<>(record.topic(), record.key(), record.payload()))
						.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
			}
			requireOwnership(claim);
			commitAttempted = true;
			producer.commitTransaction();
			// An unknown commit outcome MUST leave the whole batch pending, with its
			// original event IDs.
			if (!store.published(claim, records)) {
				throw new IllegalStateException("Outbox ownership lost before checkpoint");
			}
			success = true;
			log.info("Outbox batch published instanceId={} shard={} token={} count={} firstOutboxId={}", instanceId,
					claim.shard(), claim.token(), records.size(), records.getFirst().id());
		} catch (Exception error) {
			if (producer != null && transactionStarted && !commitAttempted) {
				try {
					producer.abortTransaction();
				} catch (Exception abortError) {
					error.addSuppressed(abortError);
				}
			}
			if (error instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			// No producer DLQ bypass: a failed early event blocks this shard until it can
			// be replayed safely.
			log.warn("Outbox batch retained for retry instanceId={} shard={} token={}", instanceId, claim.shard(),
					claim.token(), error);
		} finally {
			if (producer != null) {
				try {
					producer.close(Duration.ofMillis(operationTimeoutMillis));
				} catch (Exception error) {
					log.warn("Outbox producer close failed shard={}", claim.shard(), error);
				}
			}
			// At most one bounded batch per claim gives new replicas a chance to acquire
			// busy shards.
			store.release(claim, success ? 0 : 5000);
		}
	}

	private void requireOwnership(ShardClaim claim) {
		if (stopping.get() || !store.renew(claim, leaseMillis)) {
			throw new IllegalStateException("Outbox claim expired or replaced");
		}
	}

	@Override
	public synchronized void close() {
		stopping.set(true);
		executor.shutdown();
		try {
			if (!executor.awaitTermination(2 * operationTimeoutMillis, TimeUnit.MILLISECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException interrupted) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
