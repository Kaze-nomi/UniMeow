package uni.feed.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import uni.feed.kafka.producer.FeedDlqProducer;
import uni.feed.repository.FeedProjectionRepository;
import uni.feed.service.FeedEventService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class FeedDlqIntegrationTest {

	@Test
	void invalidEventIsDurablyDeadLetteredThenCorrectedPayloadReplaysWithOriginalId() throws Exception {
		String bootstrap = System.getenv("INTEGRATION_KAFKA_BOOTSTRAP");
		String prefix = "consumer-test-" + UUID.randomUUID();
		String source = prefix + "-source";
		String dlq = prefix + "-dlq";
		String id = prefix + "-id";
		String post = prefix + "-post";
		String author = prefix + "-author";
		ObjectMapper mapper = new ObjectMapper();
		try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
			admin.createTopics(List.of(new NewTopic(source, 1, (short) 1), new NewTopic(dlq, 1, (short) 1))).all()
					.get();
		}
		var connection = new LettuceConnectionFactory("127.0.0.1",
				Integer.parseInt(System.getenv("INTEGRATION_REDIS_PORT")));
		connection.afterPropertiesSet();
		var redis = new StringRedisTemplate(connection);
		var factory = new DefaultKafkaProducerFactory<String, String>(Map.of("bootstrap.servers", bootstrap,
				"key.serializer", "org.apache.kafka.common.serialization.StringSerializer", "value.serializer",
				"org.apache.kafka.common.serialization.StringSerializer", "acks", "all"));
		var template = new KafkaTemplate<>(factory);
		try (var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", bootstrap, "group.id", prefix,
				"enable.auto.commit", "false", "isolation.level", "read_committed", "auto.offset.reset", "earliest",
				"key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer", "value.deserializer",
				"org.apache.kafka.common.serialization.StringDeserializer"))) {
			FeedDlqProducer producer = new FeedDlqProducer(template, mapper);
			ReflectionTestUtils.setField(producer, "feedEventsDlqTopic", dlq);
			FeedEventService service = new FeedEventService(mapper, new FeedProjectionRepository(redis));
			for (String field : List.of("authorWindowSize", "userWindowSize", "uniWindowSize", "popularWindowSize")) {
				ReflectionTestUtils.setField(service, field, 1000L);
			}
			FeedKafkaConsumer listener = new FeedKafkaConsumer(service, producer);
			String invalid = "{\"eventId\":\"" + id + "\",\"eventType\":\"POST_CREATED\",\"payload\":{\"postId\":\""
					+ post + "\"}}";
			AtomicBoolean acknowledged = new AtomicBoolean();
			listener.onPostEvent(invalid, () -> acknowledged.set(true), source, author, 0, 19);
			assertThat(acknowledged).isTrue();
			consumer.assign(List.of(new TopicPartition(dlq, 0)));
			ConsumerRecord<String, String> failed = receive(consumer);
			var failure = mapper.readTree(failed.value());
			assertThat(failure.path("payload").path("eventId").asText()).isEqualTo(id);
			assertThat(failure.path("sourceOffset").asLong()).isEqualTo(19);
			assertThat(redis.opsForHash().get("feed:receipt:" + id, "done")).isNull();
			String corrected = """
					{"eventId":"%s","eventType":"POST_CREATED","occurredAt":"2026-01-01T00:00:00Z","payload":{"postId":"%s","authorId":"%s"}}
					"""
					.formatted(id, post, author);
			consumer.assign(List.of(new TopicPartition(source, 0)));
			for (int attempt = 0; attempt < 2; attempt++) {
				template.send(source, author, corrected).join();
				ConsumerRecord<String, String> replay = receive(consumer);
				acknowledged.set(false);
				listener.onPostEvent(replay.value(), () -> acknowledged.set(true), replay.topic(), replay.key(),
						replay.partition(), replay.offset());
				assertThat(acknowledged).isTrue();
			}
			assertThat(redis.opsForZSet().zCard("feed:author:" + author)).isEqualTo(1);
			assertThat(redis.opsForHash().get("feed:receipt:" + id, "done")).isEqualTo("1");
		} finally {
			template.destroy();
			factory.destroy();
			connection.destroy();
		}
	}

	private ConsumerRecord<String, String> receive(KafkaConsumer<String, String> consumer) {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (System.nanoTime() < deadline) {
			var records = consumer.poll(Duration.ofMillis(100));
			if (!records.isEmpty())
				return records.iterator().next();
		}
		throw new AssertionError("No Kafka record received");
	}
}
