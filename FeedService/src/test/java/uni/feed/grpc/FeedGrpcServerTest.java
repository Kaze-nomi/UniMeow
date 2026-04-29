package uni.feed.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.feed.record.FeedPageResult;
import uni.feed.service.FeedReadService;
import uni.grpc.feed.FeedType;
import uni.grpc.feed.GetFeedRequest;
import uni.grpc.feed.GetFeedResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedGrpcServerTest {

	@Mock
	FeedReadService feedReadService;

	@Mock
	StreamObserver<GetFeedResponse> responseObserver;

	@InjectMocks
	FeedGrpcServer feedGrpcServer;

	@Test
	void getFeed_following_routes_to_following_service_and_returns_page() {
		GetFeedRequest request = GetFeedRequest.newBuilder().setFeedType(FeedType.FOLLOWING).setUserId("user-123")
				.setSize(20).build();

		FeedPageResult pageResult = new FeedPageResult(List.of("post1", "post2", "post3"), 1700000000000L, true);

		when(feedReadService.getFollowingFeed("user-123", null, 20, null, null, null)).thenReturn(pageResult);

		feedGrpcServer.getFeed(request, responseObserver);

		ArgumentCaptor<GetFeedResponse> captor = ArgumentCaptor.forClass(GetFeedResponse.class);
		verify(responseObserver).onNext(captor.capture());
		GetFeedResponse response = captor.getValue();

		assertThat(response.getPostIdsList()).containsExactlyInAnyOrder("post1", "post2", "post3");
		assertThat(response.getHasMore()).isTrue();
		assertThat(response.getNextCursor()).isEqualTo(1700000000000L);
		verify(feedReadService).getFollowingFeed("user-123", null, 20, null, null, null);
		verify(responseObserver).onCompleted();
	}

	@Test
	void getFeed_trending_routes_to_trending_service() {
		GetFeedRequest request = GetFeedRequest.newBuilder().setFeedType(FeedType.TRENDING).setCursor(100).setSize(20)
				.build();

		FeedPageResult pageResult = new FeedPageResult(List.of("post1"), 99L, true);
		when(feedReadService.getTrendingFeed(100L, 20, null, null, null)).thenReturn(pageResult);

		feedGrpcServer.getFeed(request, responseObserver);

		verify(feedReadService).getTrendingFeed(100L, 20, null, null, null);
		verify(responseObserver).onCompleted();
	}

	@Test
	void getFeed_global_routes_to_global_service() {
		GetFeedRequest request = GetFeedRequest.newBuilder().setFeedType(FeedType.GLOBAL).setSize(10).build();

		FeedPageResult pageResult = new FeedPageResult(List.of("p1"), null, false);
		when(feedReadService.getGlobalFeed(null, 10, null, null, null)).thenReturn(pageResult);

		feedGrpcServer.getFeed(request, responseObserver);

		verify(feedReadService).getGlobalFeed(null, 10, null, null, null);
		verify(responseObserver).onCompleted();
	}

	@Test
	void getFeed_following_without_user_id_returns_unauthenticated() {
		GetFeedRequest request = GetFeedRequest.newBuilder().setFeedType(FeedType.FOLLOWING).setSize(20).build();

		feedGrpcServer.getFeed(request, responseObserver);

		ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
		verify(responseObserver).onError(errorCaptor.capture());

		Status status = Status.fromThrowable(errorCaptor.getValue());
		assertThat(status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
		verifyNoInteractions(feedReadService);
	}

	@Test
	void getFeed_treats_zero_cursor_and_non_positive_size_as_nulls() {
		GetFeedRequest request = GetFeedRequest.newBuilder().setFeedType(FeedType.TRENDING).setCursor(0).setSize(0)
				.build();

		FeedPageResult pageResult = new FeedPageResult(List.of(), null, false);
		when(feedReadService.getTrendingFeed(null, null, null, null, null)).thenReturn(pageResult);

		feedGrpcServer.getFeed(request, responseObserver);

		verify(feedReadService).getTrendingFeed(null, null, null, null, null);
	}

	@Test
	void getFeed_omits_next_cursor_when_result_has_no_cursor() {
		GetFeedRequest request = GetFeedRequest.newBuilder().setFeedType(FeedType.GLOBAL).setSize(20).build();

		FeedPageResult pageResult = new FeedPageResult(List.of(), null, false);
		when(feedReadService.getGlobalFeed(null, 20, null, null, null)).thenReturn(pageResult);

		feedGrpcServer.getFeed(request, responseObserver);

		ArgumentCaptor<GetFeedResponse> captor = ArgumentCaptor.forClass(GetFeedResponse.class);
		verify(responseObserver).onNext(captor.capture());

		assertThat(captor.getValue().getPostIdsList()).isEmpty();
		assertThat(captor.getValue().getHasMore()).isFalse();
		assertThat(captor.getValue().getNextCursor()).isEqualTo(0L);
	}

	@Test
	void getFeed_handles_service_exception() {
		GetFeedRequest request = GetFeedRequest.newBuilder().setFeedType(FeedType.GLOBAL).setSize(20).build();

		when(feedReadService.getGlobalFeed(null, 20, null, null, null))
				.thenThrow(new RuntimeException("Redis connection failed"));

		feedGrpcServer.getFeed(request, responseObserver);

		ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
		verify(responseObserver).onError(errorCaptor.capture());
		Status status = Status.fromThrowable(errorCaptor.getValue());
		assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
	}

	@Test
	void getFeed_passes_university_and_program_scope() {
		GetFeedRequest request = GetFeedRequest.newBuilder().setFeedType(FeedType.GLOBAL).setUniversityId(7L)
				.setFacultyId(3L).setProgramId(9L).setSize(10).build();

		when(feedReadService.getGlobalFeed(null, 10, 7L, 3L, 9L))
				.thenReturn(new FeedPageResult(List.of("p1"), null, false));

		feedGrpcServer.getFeed(request, responseObserver);

		verify(feedReadService).getGlobalFeed(null, 10, 7L, 3L, 9L);
		verify(responseObserver).onCompleted();
	}
}
