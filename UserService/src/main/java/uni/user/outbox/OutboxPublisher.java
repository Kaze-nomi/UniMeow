package uni.user.outbox;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import uni.outbox.JdbcOutboxStore;
import uni.outbox.ShardPublisher;
import uni.outbox.TransactionalProducers;

@Component
public class OutboxPublisher {
    private final ShardPublisher publisher;

    public OutboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            KafkaTemplate<String, String> kafka, MeterRegistry metrics,
            @Value("${app.outbox.namespace}") String namespace,
            @Value("${app.outbox.workers:2}") int workers,
            @Value("${app.outbox.batch-size:50}") int batchSize,
            @Value("${app.outbox.lease-ms:45000}") long leaseMillis,
            @Value("${app.outbox.max-block-ms:10000}") int maxBlockMillis,
            @Value("${app.outbox.transaction-timeout-ms:30000}") int transactionTimeoutMillis) {
        JdbcOutboxStore store = new JdbcOutboxStore(jdbc, transactions);
        Gauge.builder("outbox.pending.events", store, JdbcOutboxStore::pendingCount).register(metrics);
        Gauge.builder("outbox.oldest.pending.seconds", store, JdbcOutboxStore::oldestPendingSeconds).register(metrics);
        publisher = new ShardPublisher(store,
                TransactionalProducers.factory(kafka.getProducerFactory().getConfigurationProperties(),
                        namespace, "user-service", maxBlockMillis, transactionTimeoutMillis),
                workers, batchSize, leaseMillis, maxBlockMillis, transactionTimeoutMillis);
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay-ms:1500}")
    public void publishPendingEvents() {
        publisher.publishPendingEvents();
    }

    @PreDestroy
    public void close() {
        publisher.close();
    }
}
