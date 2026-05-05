package uni.gateway.grpc;

import com.google.protobuf.ByteString;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uni.grpc.media.MediaServiceGrpc;
import uni.grpc.media.UploadFileRequest;

@Component
public class MediaGrpcClient {

	@GrpcClient("media-service")
	private MediaServiceGrpc.MediaServiceBlockingStub stub;

	public Mono<String> uploadFile(String bucket, String filename, String contentType, byte[] data) {
		return Mono.fromCallable(() -> {
			UploadFileRequest req = UploadFileRequest.newBuilder().setBucket(bucket).setFilename(filename)
					.setContentType(contentType).setData(ByteString.copyFrom(data)).build();
			return stub.uploadFile(req).getUrl();
		}).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}
}
