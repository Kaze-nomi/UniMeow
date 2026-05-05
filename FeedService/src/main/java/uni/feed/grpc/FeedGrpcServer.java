package uni.feed.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import uni.feed.record.FeedPageResult;
import uni.feed.service.FeedReadService;
import uni.grpc.feed.FeedServiceGrpc;
import uni.grpc.feed.GetFeedRequest;
import uni.grpc.feed.GetFeedResponse;

@GrpcService
@RequiredArgsConstructor
public class FeedGrpcServer extends FeedServiceGrpc.FeedServiceImplBase {

	private final FeedReadService feedReadService;

	@Override
	public void getFeed(GetFeedRequest req, StreamObserver<GetFeedResponse> obs) {
		try {
			FeedPageResult result = feedReadService.getFeed(req.getFeedType().name(), req.getUserId(), req.getCursor(),
					req.getSize(), req.getUniversityId(), req.getFacultyId(), req.getProgramId(), req.getTopicId());

			GetFeedResponse.Builder builder = GetFeedResponse.newBuilder().addAllPostIds(result.postIds())
					.setHasMore(result.hasMore());

			if (result.nextCursor() != null) {
				builder.setNextCursor(result.nextCursor());
			}

			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.UNAUTHENTICATED.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}
}
