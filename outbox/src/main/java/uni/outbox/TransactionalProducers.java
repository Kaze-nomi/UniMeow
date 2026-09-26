package uni.outbox;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

public final class TransactionalProducers {
	private TransactionalProducers() {
	}

	public static IntFunction<Producer<String, String>> factory(Map<String, Object> baseProperties, String namespace,
			String service, int maxBlockMillis, int transactionTimeoutMillis) {
		if (namespace == null || !namespace.matches("[A-Za-z0-9._-]{1,80}")) {
			throw new IllegalArgumentException(
					"app.outbox.namespace must identify this installation (1-80 safe characters)");
		}
		return shard -> {
			Map<String, Object> properties = new HashMap<>(baseProperties);
			properties.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, namespace + "." + service + ".shard-" + shard);
			properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
			properties.put(ProducerConfig.ACKS_CONFIG, "all");
			properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMillis);
			properties.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, transactionTimeoutMillis);
			properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, maxBlockMillis);
			properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, maxBlockMillis + 1000);
			properties.put(ProducerConfig.CLIENT_ID_CONFIG, service + "-shard-" + shard);
			return new KafkaProducer<>(properties);
		};
	}
}
