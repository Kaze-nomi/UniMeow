package uni.outbox;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class OutboxProtocolIntegrationTest {
	private JdbcTemplate jdbc;
	private JdbcOutboxStore store;
	private TransactionTemplate transactions;
	private String topic;
	private String namespace;
	private IntFunction<Producer<String, String>> producers;

	@BeforeEach
	void isolatedSchemaAndTopic() throws Exception {
		String url = System.getenv("OUTBOX_IT_JDBC_URL");
		String schema = "outbox_it_" + UUID.randomUUID().toString().replace("-", "");
		DriverManagerDataSource admin = new DriverManagerDataSource(url, "outbox_test", "synthetic-test-only");
		new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, "outbox_test",
				"synthetic-test-only");
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute(Files
				.readString(Path.of("../UserService/src/main/resources/db/migration/V5__create_outbox_events.sql")));
		jdbc.execute(
				Files.readString(Path.of("../UserService/src/main/resources/db/migration/V11__outbox_shards.sql")));
		DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
		transactions = new TransactionTemplate(manager);
		store = new JdbcOutboxStore(jdbc, manager);
		namespace = "outbox-it-" + UUID.randomUUID();
		topic = "outbox-it-" + UUID.randomUUID();
		Map<String, Object> config = Map.of("bootstrap.servers", System.getenv("OUTBOX_IT_KAFKA"), "key.serializer",
				StringSerializer.class, "value.serializer", StringSerializer.class);
		producers = TransactionalProducers.factory(config, namespace, "test", 10000, 30000);
		try (Admin kafka = Admin.create(Map.of("bootstrap.servers", System.getenv("OUTBOX_IT_KAFKA")))) {
			kafka.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
		}
	}

	private UUID insert(String key, String payload) {
		UUID id = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO outbox_events(id,topic,event_key,payload,created_at) VALUES(?,?,?,?::jsonb, '2099-01-01')",
				id, topic, key, payload);
		return id;
	}

	private int shard(String key) {
		return jdbc.queryForObject("SELECT outbox_shard(?,?)", Integer.class, topic, key);
	}

	private void expire(ShardClaim claim) {
		jdbc.update(
				"UPDATE outbox_shard_owners SET lease_until = clock_timestamp() - interval '1 second' WHERE shard_id = ?",
				claim.shard());
	}

	@Test
	void additiveMigrationPreservesLegacyBacklogAndOldInsertShape() throws Exception {
		String url = System.getenv("OUTBOX_IT_JDBC_URL");
		String schema = "legacy_it_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.execute("CREATE SCHEMA " + schema);
		DriverManagerDataSource legacySource = new DriverManagerDataSource(
				url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, "outbox_test",
				"synthetic-test-only");
		JdbcTemplate legacy = new JdbcTemplate(legacySource);
		legacy.execute(Files
				.readString(Path.of("../UserService/src/main/resources/db/migration/V5__create_outbox_events.sql")));
		UUID id = UUID.randomUUID();
		legacy.update("""
				INSERT INTO outbox_events(id,topic,event_key,payload,created_at)
				VALUES(?,?,?,?::jsonb, timestamp '2099-01-01')
				""", id, topic, "same", "{\"eventId\":\"legacy-id\",\"version\":1}");
		legacy.execute(
				Files.readString(Path.of("../UserService/src/main/resources/db/migration/V11__outbox_shards.sql")));
		legacy.update("""
				INSERT INTO outbox_events(id,topic,event_key,payload,created_at)
				VALUES(?,?,?,?::jsonb, timestamp '2000-01-01')
				""", UUID.randomUUID(), topic, "same", "{\"eventId\":\"new-id\",\"version\":1}");
		JdbcOutboxStore migrated = new JdbcOutboxStore(legacy, new DataSourceTransactionManager(legacySource));
		ShardClaim claim = migrated.claim(UUID.randomUUID(), 45000).orElseThrow();
		List<OutboxRecord> records = migrated.pending(claim, 50);
		assertThat(records.getFirst().id()).isEqualTo(id);
		assertThat(records.getFirst().payload()).contains("legacy-id");
		assertThat(records.getLast().payload()).contains("new-id");
		assertThat(legacy.queryForObject("SELECT created_at = timestamp '2099-01-01' FROM outbox_events WHERE id = ?",
				Boolean.class, id)).isTrue();
		assertThat(legacy.queryForList(
				"""
						SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'outbox_events'
						ORDER BY ordinal_position
						""",
				String.class, schema))
				.containsExactly("id", "topic", "event_key", "payload", "created_at", "published_at");
	}

	@Test
	void pendingShardQueryUsesMeasuredPartialIndex() {
		jdbc.update("""
				INSERT INTO outbox_events(id,topic,event_key,payload,created_at)
				SELECT gen_random_uuid(), ?, 'bulk-' || n, '{}', now() FROM generate_series(1,20000) AS n
				""", topic);
		jdbc.execute("ANALYZE outbox_events");
		List<String> plan = jdbc.query("""
				EXPLAIN (ANALYZE, BUFFERS) SELECT id, topic, event_key, payload FROM outbox_events
				WHERE published_at IS NULL AND outbox_shard(topic,event_key) = 3 ORDER BY created_at,id LIMIT 50
				""", (row, index) -> row.getString(1));
		assertThat(String.join("\n", plan)).contains("idx_outbox_events_shard_pending");
		System.out.println("Synthetic 20k-row pending query plan: " + plan);
	}

	@Test
	void explicitKafkaAbortIsInvisibleToConsumers() throws Exception {
		try (Producer<String, String> producer = producers.apply(0)) {
			producer.initTransactions();
			producer.beginTransaction();
			producer.send(new ProducerRecord<>(topic, "same", "aborted")).get(10, TimeUnit.SECONDS);
			producer.abortTransaction();
			producer.beginTransaction();
			producer.send(new ProducerRecord<>(topic, "same", "committed")).get(10, TimeUnit.SECONDS);
			producer.commitTransaction();
		}
		assertThat(readCommitted(1)).containsExactly("committed");
	}

	@Test
	void twoReplicasClaimDifferentShardsAndStaleTokensCannotCheckpoint() throws Exception {
		insert("one", "{}");
		String different = "two";
		while (shard(different) == shard("one")) {
			different += "x";
		}
		insert(different, "{}");
		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> store.claim(UUID.randomUUID(), 45000).orElseThrow());
			var b = executor.submit(() -> store.claim(UUID.randomUUID(), 45000).orElseThrow());
			ShardClaim first = a.get(10, TimeUnit.SECONDS);
			ShardClaim second = b.get(10, TimeUnit.SECONDS);
			assertThat(first.shard()).isNotEqualTo(second.shard());
			List<OutboxRecord> pending = store.pending(first, 50);
			expire(first);
			ShardClaim replacement = store.claim(UUID.randomUUID(), 45000).orElseThrow();
			assertThat(replacement.shard()).isEqualTo(first.shard());
			assertThat(store.renew(first, 45000)).isFalse();
			assertThat(store.published(first, pending)).isFalse();
			store.release(first, 0);
			assertThat(store.renew(replacement, 45000)).isTrue();
		}
	}

	@Test
	void twoPublishersPublishDifferentShardsAtTheSameTime() throws Exception {
		insert("one", "{\"eventId\":\"one\"}");
		String different = "two";
		while (shard(different) == shard("one")) {
			different += "x";
		}
		insert(different, "{\"eventId\":\"two\"}");
		CountDownLatch bothClaimed = new CountDownLatch(2);
		IntFunction<Producer<String, String>> concurrentFactory = shard -> {
			bothClaimed.countDown();
			try {
				if (!bothClaimed.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Second replica never acquired another shard");
				}
			} catch (InterruptedException error) {
				throw new RuntimeException(error);
			}
			return producers.apply(shard);
		};
		try (var executor = Executors.newFixedThreadPool(2);
				ShardPublisher a = new ShardPublisher(store, concurrentFactory, 2, 50, 45000, 10000, 30000);
				ShardPublisher b = new ShardPublisher(store, concurrentFactory, 2, 50, 45000, 10000, 30000)) {
			var first = executor.submit(a::publishOneBatch);
			var second = executor.submit(b::publishOneBatch);
			first.get(30, TimeUnit.SECONDS);
			second.get(30, TimeUnit.SECONDS);
		}
		assertThat(store.pendingCount()).isZero();
		assertThat(readCommitted(2)).containsExactlyInAnyOrder("{\"eventId\": \"one\"}", "{\"eventId\": \"two\"}");
	}

	@Test
	void databaseClockAndCommitSerializationDefeatClockSkewAndInvisibleEarlierInserts() throws Exception {
		CountDownLatch inserted = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> transactions.execute(status -> {
				insert("same", "{\"eventId\":\"create\"}");
				inserted.countDown();
				try {
					assertThat(commit.await(10, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException error) {
					throw new RuntimeException(error);
				}
				return true;
			}));
			assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
			var second = executor.submit(() -> insert("same", "{\"eventId\":\"delete\"}"));
			Thread.sleep(150);
			assertThat(second.isDone()).isFalse();
			assertThat(store.claim(UUID.randomUUID(), 45000)).isEmpty();
			commit.countDown();
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		}
		ShardClaim claim = store.claim(UUID.randomUUID(), 45000).orElseThrow();
		assertThat(store.pending(claim, 50)).extracting(OutboxRecord::payload)
				.containsExactly("{\"eventId\": \"create\"}", "{\"eventId\": \"delete\"}");
		assertThat(jdbc.queryForObject("SELECT max(created_at) < timestamp '2099-01-01' FROM outbox_events",
				Boolean.class)).isTrue();
	}

	@Test
	void takeoverFencesOldProducerAndReadCommittedHidesItsAbortedWork() throws Exception {
		insert("same", "{}");
		ShardClaim old = store.claim(UUID.randomUUID(), 45000).orElseThrow();
		try (Producer<String, String> a = producers.apply(old.shard())) {
			a.initTransactions();
			a.beginTransaction();
			a.send(new ProducerRecord<>(topic, "same", "aborted-old")).get(10, TimeUnit.SECONDS);
			expire(old);
			ShardClaim next = store.claim(UUID.randomUUID(), 45000).orElseThrow();
			try (Producer<String, String> b = producers.apply(next.shard())) {
				b.initTransactions();
				assertThatThrownBy(a::commitTransaction).isInstanceOf(KafkaException.class);
				assertThat(store.renew(old, 45000)).isFalse();
				b.beginTransaction();
				b.send(new ProducerRecord<>(topic, "same", "committed-new")).get(10, TimeUnit.SECONDS);
				b.commitTransaction();
			}
		}
		assertThat(readCommitted(1)).containsExactly("committed-new");
	}

	@Test
	void delayedOldInitFencesNewOwnerOnceButFreshClaimRecoversWithoutStalePublication() throws Exception {
		insert("same", "{\"eventId\":\"original\"}");
		ShardClaim old = store.claim(UUID.randomUUID(), 45000).orElseThrow();
		try (Producer<String, String> delayed = producers.apply(old.shard())) {
			expire(old);
			ShardClaim current = store.claim(UUID.randomUUID(), 45000).orElseThrow();
			try (Producer<String, String> active = producers.apply(current.shard())) {
				active.initTransactions();
				active.beginTransaction();
				active.send(new ProducerRecord<>(topic, "same", "uncommitted-current")).get(10, TimeUnit.SECONDS);
				delayed.initTransactions();
				assertThat(store.renew(old, 45000)).isFalse();
				assertThatThrownBy(active::commitTransaction).isInstanceOf(KafkaException.class);
				store.release(current, 0);
			}
		}
		try (ShardPublisher publisher = new ShardPublisher(store, producers, 2, 50, 45000, 10000, 30000)) {
			publisher.publishOneBatch();
		}
		assertThat(readCommitted(1)).containsExactly("{\"eventId\": \"original\"}");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class))
				.isZero();
	}

	@Test
	void commitBeforeDatabaseCheckpointReplaysSameIdBeforeLaterBatch() throws Exception {
		insert("same", "{\"eventId\":\"create\"}");
		insert("same", "{\"eventId\":\"delete\"}");
		ShardClaim lost = store.claim(UUID.randomUUID(), 45000).orElseThrow();
		OutboxRecord first = store.pending(lost, 1).getFirst();
		try (Producer<String, String> producer = producers.apply(lost.shard())) {
			producer.initTransactions();
			producer.beginTransaction();
			producer.send(new ProducerRecord<>(first.topic(), first.key(), first.payload())).get(10, TimeUnit.SECONDS);
			producer.commitTransaction();
			// Simulated process death: the Kafka commit succeeded, the DB checkpoint never
			// ran.
		}
		expire(lost);
		try (ShardPublisher publisher = new ShardPublisher(store, producers, 2, 1, 45000, 10000, 30000)) {
			publisher.publishOneBatch();
			publisher.publishOneBatch();
		}
		assertThat(readCommitted(3)).containsExactly("{\"eventId\": \"create\"}", "{\"eventId\": \"create\"}",
				"{\"eventId\": \"delete\"}");
	}

	private List<String> readCommitted(int expected) {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getenv("OUTBOX_IT_KAFKA"));
		config.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID());
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
			List<TopicPartition> partitions = List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1),
					new TopicPartition(topic, 2));
			consumer.assign(partitions);
			consumer.seekToBeginning(partitions);
			List<String> result = new ArrayList<>();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
			while (result.size() < expected && System.nanoTime() < deadline) {
				consumer.poll(Duration.ofMillis(300)).forEach(record -> result.add(record.value()));
			}
			// Also poll past the expected count to expose mistakenly visible aborted
			// records.
			consumer.poll(Duration.ofMillis(500)).forEach(record -> result.add(record.value()));
			return result;
		}
	}
}
