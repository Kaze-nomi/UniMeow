package uni.outbox;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShardPublisherTest {
	private final OutboxStore store = mock(OutboxStore.class);
	@SuppressWarnings("unchecked")
	private final Producer<String, String> producer = mock(Producer.class);
	private final ShardClaim claim = new ShardClaim(3, UUID.randomUUID(), UUID.randomUUID());
	private final OutboxRecord record = new OutboxRecord(UUID.randomUUID(), "events", "author",
			"{\"eventId\":\"original\"}");

	@BeforeEach
	void claim() {
		when(store.claim(any(), anyLong())).thenReturn(Optional.of(claim));
		when(store.renew(claim, 45000)).thenReturn(true);
	}

	private ShardPublisher publisher() {
		return new ShardPublisher(store, shard -> producer, 2, 50, 45000, 10000, 30000);
	}

	@Test
	void commitsKafkaBeforeShortDatabaseCheckpointAndPreservesPayload() {
		when(store.pending(claim, 50)).thenReturn(List.of(record));
		when(producer.send(any())).thenReturn(CompletableFuture.<RecordMetadata>completedFuture(null));
		when(store.published(claim, List.of(record))).thenReturn(true);
		try (ShardPublisher publisher = publisher()) {
			publisher.publishOneBatch();
		}
		var order = inOrder(store, producer);
		order.verify(store).claim(any(), anyLong());
		order.verify(store).renew(claim, 45000);
		order.verify(producer).initTransactions();
		order.verify(store).renew(claim, 45000);
		order.verify(store).pending(claim, 50);
		order.verify(producer).beginTransaction();
		order.verify(store).renew(claim, 45000);
		order.verify(producer).send(any());
		order.verify(store).renew(claim, 45000);
		order.verify(producer).commitTransaction();
		order.verify(store).published(claim, List.of(record));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<ProducerRecord<String, String>> message = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(producer).send(message.capture());
		assertThat(message.getValue().key()).isEqualTo(record.key());
		assertThat(message.getValue().value()).isEqualTo(record.payload());
		verify(store).release(claim, 0);
	}

	@Test
	void delayedOldInitializationNeverReadsOrSendsAStaleBatch() {
		when(store.renew(claim, 45000)).thenReturn(true, false);
		try (ShardPublisher publisher = publisher()) {
			publisher.publishOneBatch();
		}
		verify(producer).initTransactions();
		verify(store, never()).pending(any(), anyInt());
		verify(producer, never()).beginTransaction();
		verify(store, never()).published(any(), any());
		verify(store).release(claim, 5000);
	}

	@Test
	void unknownCommitLeavesBatchPendingAndDoesNotAdvanceToLaterEvents() {
		when(store.pending(claim, 50)).thenReturn(List.of(record));
		when(producer.send(any())).thenReturn(CompletableFuture.<RecordMetadata>completedFuture(null));
		doThrow(new org.apache.kafka.common.errors.TimeoutException("unknown result")).when(producer)
				.commitTransaction();
		try (ShardPublisher publisher = publisher()) {
			publisher.publishOneBatch();
		}
		verify(store, never()).published(any(), any());
		verify(store).release(claim, 5000);
		verify(producer, times(1)).initTransactions();
	}

	@Test
	void fencingStopsClaimWithoutReinitializing() {
		when(store.pending(claim, 50)).thenReturn(List.of(record));
		doThrow(new ProducerFencedException("replaced")).when(producer).beginTransaction();
		try (ShardPublisher publisher = publisher()) {
			publisher.publishOneBatch();
		}
		verify(producer, times(1)).initTransactions();
		verify(producer, never()).send(any());
		verify(store, never()).published(any(), any());
	}

	@Test
	void losingOwnershipAfterKafkaCommitCannotBeReportedAsSuccess() {
		when(store.pending(claim, 50)).thenReturn(List.of(record));
		when(producer.send(any())).thenReturn(CompletableFuture.<RecordMetadata>completedFuture(null));
		when(store.published(claim, List.of(record))).thenReturn(false);
		try (ShardPublisher publisher = publisher()) {
			publisher.publishOneBatch();
		}
		verify(store).release(claim, 5000);
	}
}
