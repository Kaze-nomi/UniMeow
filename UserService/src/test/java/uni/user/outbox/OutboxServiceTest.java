package uni.user.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

	@Mock
	OutboxEventRepository outboxEventRepository;

	@Spy
	ObjectMapper objectMapper = new ObjectMapper();

	@InjectMocks
	OutboxService outboxService;

	@Test
	void enqueueUserEvent_saves_event_with_correct_structure() {
		ReflectionTestUtils.setField(outboxService, "userEventsTopic", "user-events");

		Map<String, Object> payload = Map.of("userId", "user-123", "eventName", "USER_REGISTERED");

		outboxService.enqueueUserEvent("USER_REGISTERED", "key-1", "user-123", payload);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		OutboxEvent event = captor.getValue();
		assertThat(event.getTopic()).isEqualTo("user-events");
		assertThat(event.getEventKey()).isEqualTo("key-1");
		assertThat(event.getPayload()).contains("eventType", "USER_REGISTERED");
		assertThat(event.getPayload()).contains("eventId");
		assertThat(event.getPayload()).contains("occurredAt");
		assertThat(event.getPayload()).contains("aggregateId", "user-123");
	}

	@Test
	void enqueueUserEvent_includes_payload_in_event() {
		ReflectionTestUtils.setField(outboxService, "userEventsTopic", "user-events");

		Map<String, Object> payload = Map.of("email", "test@uni.edu", "username", "testuser");

		outboxService.enqueueUserEvent("USER_REGISTERED", "key-1", "user-123", payload);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		OutboxEvent event = captor.getValue();
		assertThat(event.getPayload()).contains("\"email\"").contains("\"test@uni.edu\"").contains("\"username\"")
				.contains("\"testuser\"");
	}

	@Test
	void enqueueUserEvent_generates_unique_event_ids() {
		ReflectionTestUtils.setField(outboxService, "userEventsTopic", "user-events");

		Map<String, Object> payload = Map.of();

		outboxService.enqueueUserEvent("USER_REGISTERED", "key-1", "user-1", payload);
		outboxService.enqueueUserEvent("USER_FOLLOWED", "key-2", "user-2", payload);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository, times(2)).save(captor.capture());

		var events = captor.getAllValues();
		assertThat(events.get(0).getPayload()).isNotEqualTo(events.get(1).getPayload());
	}

	@Test
	void enqueueUserEvent_sets_version_to_one() {
		ReflectionTestUtils.setField(outboxService, "userEventsTopic", "user-events");

		outboxService.enqueueUserEvent("USER_REGISTERED", "key-1", "user-123", Map.of());

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		assertThat(captor.getValue().getPayload()).contains("\"version\":1");
	}

	@Test
	void enqueueUserEvent_creates_outbox_event_with_no_published_at() {
		ReflectionTestUtils.setField(outboxService, "userEventsTopic", "user-events");

		outboxService.enqueueUserEvent("USER_DELETED", "key-1", "user-123", Map.of());

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		assertThat(captor.getValue().getPublishedAt()).isNull();
	}

	@Test
	void enqueueUserEvent_throws_when_serialization_fails() {
		ReflectionTestUtils.setField(outboxService, "userEventsTopic", "user-events");

		OutboxService service = new OutboxService(outboxEventRepository, new ObjectMapper() {
			@Override
			public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
				throw new com.fasterxml.jackson.core.JsonProcessingException("Test error") {
				};
			}
		});

		assertThatThrownBy(() -> service.enqueueUserEvent("USER_REGISTERED", "key", "user-1", Map.of()))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to serialize");
	}

	@Test
	void enqueueUserEvent_uses_correct_topic_from_config() {
		ReflectionTestUtils.setField(outboxService, "userEventsTopic", "custom-user-topic");

		outboxService.enqueueUserEvent("USER_REGISTERED", "key", "user-1", Map.of());

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		assertThat(captor.getValue().getTopic()).isEqualTo("custom-user-topic");
	}

	@Test
	void enqueueUserEvent_preserves_payload_data() {
		ReflectionTestUtils.setField(outboxService, "userEventsTopic", "user-events");

		Map<String, Object> payload = Map.of("userId", "u1", "email", "user@uni.edu", "subscribed", true, "timestamp",
				1234567890L);

		outboxService.enqueueUserEvent("USER_REGISTERED", "key-1", "u1", payload);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		String eventPayload = captor.getValue().getPayload();
		assertThat(eventPayload).contains("\"userId\"").contains("\"u1\"").contains("\"email\"")
				.contains("\"user@uni.edu\"").contains("\"subscribed\"").contains("true");
	}

	@Test
	void enqueueUserEvent_stores_with_user_events_topic() {
		ReflectionTestUtils.setField(outboxService, "userEventsTopic", "user-events");

		outboxService.enqueueUserEvent("USER_FOLLOWED", "key-1", "user-1", Map.of());

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		assertThat(captor.getValue().getTopic()).isEqualTo("user-events");
	}
}
