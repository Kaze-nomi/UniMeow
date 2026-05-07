package uni.gateway.grpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;
import uni.grpc.feed.FeedServiceGrpc;
import uni.grpc.feed.GetFeedResponse;
import uni.grpc.feed.FeedType;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedGrpcClientTest {

	@Mock
	FeedServiceGrpc.FeedServiceBlockingStub stub;

	@InjectMocks
	FeedGrpcClient feedGrpcClient;

	@Test
	void getFeed_returns_response_with_post_ids_and_has_more() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		GetFeedResponse expectedResponse = GetFeedResponse.newBuilder().addPostIds("post1").addPostIds("post2")
				.setNextCursor(1700000000000L).setHasMore(true).build();
		when(stub.getFeed(any())).thenReturn(expectedResponse);

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-123", null, 20)).assertNext(response -> {
			assertThat(response.getPostIdsList()).containsExactly("post1", "post2");
			assertThat(response.getHasMore()).isTrue();
			assertThat(response.getNextCursor()).isEqualTo(1700000000000L);
		}).verifyComplete();
	}

	@Test
	void getFeed_omits_user_id_when_null() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, null, null, 20)).expectNextCount(1)
				.verifyComplete();

		verify(stub).getFeed(argThat(req -> req.getUserId().isBlank()));
	}

	@Test
	void getFeed_omits_user_id_when_blank() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "  ", null, 20)).expectNextCount(1)
				.verifyComplete();

		verify(stub).getFeed(argThat(req -> req.getUserId().isBlank()));
	}

	@Test
	void getFeed_includes_user_id_when_not_blank() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-123", null, 20)).expectNextCount(1)
				.verifyComplete();

		verify(stub).getFeed(argThat(req -> req.getUserId().equals("user-123")));
	}

	@Test
	void getFeed_omits_cursor_when_null() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-123", null, 20)).expectNextCount(1)
				.verifyComplete();

		verify(stub).getFeed(argThat(req -> req.getCursor() == 0));
	}

	@Test
	void getFeed_includes_cursor_when_provided() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());
		long cursor = 1700000001234L;

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-123", cursor, 20)).expectNextCount(1)
				.verifyComplete();

		verify(stub).getFeed(argThat(req -> req.getCursor() == cursor));
	}

	@Test
	void getFeed_passes_size_to_stub() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-123", null, 50)).expectNextCount(1)
				.verifyComplete();

		verify(stub).getFeed(argThat(req -> req.getSize() == 50));
	}

	@Test
	void getFeed_includes_topic_id_when_provided() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-123", null, 20, -1L)).expectNextCount(1)
				.verifyComplete();

		verify(stub).getFeed(argThat(req -> req.getTopicId() == -1L));
	}

	@Test
	void getFeed_returns_empty_response() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().setHasMore(false).build());

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-empty", null, 20)).assertNext(response -> {
			assertThat(response.getPostIdsList()).isEmpty();
			assertThat(response.getHasMore()).isFalse();
		}).verifyComplete();
	}

	@Test
	void getFeed_returns_many_posts() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		GetFeedResponse.Builder builder = GetFeedResponse.newBuilder().setHasMore(true);
		for (int i = 0; i < 100; i++) {
			builder.addPostIds("post-" + i);
		}
		when(stub.getFeed(any())).thenReturn(builder.build());

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-123", null, 100))
				.assertNext(resp -> assertThat(resp.getPostIdsList()).hasSize(100)).verifyComplete();
	}

	@Test
	void getFeed_does_not_retry_failure() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);

		when(stub.getFeed(any())).thenThrow(new RuntimeException("Temporary failure"));

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-123", null, 20))
				.expectError(RuntimeException.class).verify(Duration.ofSeconds(5));

		verify(stub, times(1)).getFeed(any());
	}

	@Test
	void getFeed_fails_after_retries_exceeded() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenThrow(new RuntimeException("Connection failed"));

		StepVerifier.create(feedGrpcClient.getFeed(FeedType.TRENDING, "user-123", null, 20))
				.expectError(RuntimeException.class).verify(Duration.ofSeconds(5));
	}

	@Test
	void getUniversityFeed_sets_university_and_optional_faculty_program_ids() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());

		StepVerifier.create(feedGrpcClient.getUniversityFeed(FeedType.TRENDING, "user-123", 777L, 15, 42L, 11L, 5L))
				.expectNextCount(1).verifyComplete();

		verify(stub).getFeed(argThat(req -> req.getFeedType() == FeedType.TRENDING && req.getUniversityId() == 42L
				&& req.getFacultyId() == 11L && req.getProgramId() == 5L && req.getCursor() == 777L
				&& req.getSize() == 15 && req.getUserId().equals("user-123")));
	}

	@Test
	void getUniversityFeed_omits_optional_scope_ids_when_not_positive() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());

		StepVerifier.create(feedGrpcClient.getUniversityFeed(FeedType.TRENDING, null, null, 20, 42L, 0L, 0L))
				.expectNextCount(1).verifyComplete();

		verify(stub).getFeed(
				argThat(req -> req.getFacultyId() == 0L && req.getProgramId() == 0L && req.getUniversityId() == 42L));
	}

	@Test
	void getUniversityFeed_omits_blank_user_id() {
		ReflectionTestUtils.setField(feedGrpcClient, "stub", stub);
		when(stub.getFeed(any())).thenReturn(GetFeedResponse.newBuilder().build());

		StepVerifier.create(feedGrpcClient.getUniversityFeed(FeedType.FOLLOWING, "   ", null, 20, 7L, 3L, null))
				.expectNextCount(1).verifyComplete();

		verify(stub).getFeed(argThat(req -> req.getUserId().isBlank()));
	}
}
