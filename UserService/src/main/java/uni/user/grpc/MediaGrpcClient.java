package uni.user.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uni.grpc.media.MediaServiceGrpc;
import uni.grpc.media.UploadFileRequest;

import java.util.concurrent.TimeUnit;

@Component
public class MediaGrpcClient {

	@Value("${app.media.host:localhost}")
	private String mediaHost;

	@Value("${app.media.port:9093}")
	private int mediaPort;

	@Value("${app.media.deadline-seconds:10}")
	private int deadlineSeconds;

	private ManagedChannel channel;
	private MediaServiceGrpc.MediaServiceBlockingStub stub;

	@PostConstruct
	void init() {
		this.channel = ManagedChannelBuilder.forAddress(mediaHost, mediaPort).usePlaintext().build();
		this.stub = MediaServiceGrpc.newBlockingStub(channel).withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS);
	}

	@PreDestroy
	void shutdown() {
		if (channel != null) {
			channel.shutdown();
		}
	}

	public String uploadFile(String bucket, String filename, String contentType, byte[] data) {
		return stub
				.uploadFile(UploadFileRequest.newBuilder().setBucket(bucket).setFilename(filename)
						.setContentType(contentType).setData(com.google.protobuf.ByteString.copyFrom(data)).build())
				.getUrl();
	}
}
