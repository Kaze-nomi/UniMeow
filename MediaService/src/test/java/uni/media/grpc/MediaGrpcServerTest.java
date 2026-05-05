package uni.media.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.grpc.media.DeleteFileRequest;
import uni.grpc.media.DeleteFileResponse;
import uni.grpc.media.PresignRequest;
import uni.grpc.media.PresignResponse;
import uni.grpc.media.UploadFileRequest;
import uni.grpc.media.UploadFileResponse;
import uni.media.service.MinioService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaGrpcServerTest {

	@Mock
	MinioService minioService;

	@InjectMocks
	MediaGrpcServer mediaGrpcServer;

	@Test
	void uploadFile_returns_url_on_success() {
		when(minioService.upload("university-icons", "logo.png", "image/png", new byte[]{1}))
				.thenReturn("http://cdn/university-icons/logo.png");
		StreamObserver<UploadFileResponse> obs = mock(StreamObserver.class);

		mediaGrpcServer.uploadFile(UploadFileRequest.newBuilder().setBucket("university-icons").setFilename("logo.png")
				.setContentType("image/png").setData(com.google.protobuf.ByteString.copyFrom(new byte[]{1})).build(),
				obs);

		ArgumentCaptor<UploadFileResponse> captor = ArgumentCaptor.forClass(UploadFileResponse.class);
		verify(obs).onNext(captor.capture());
		assertThat(captor.getValue().getUrl()).isEqualTo("http://cdn/university-icons/logo.png");
	}

	@Test
	void deleteFile_returns_success_true() {
		StreamObserver<DeleteFileResponse> obs = mock(StreamObserver.class);

		mediaGrpcServer.deleteFile(DeleteFileRequest.newBuilder().setBucket("post-media").setFilename("p.png").build(),
				obs);

		ArgumentCaptor<DeleteFileResponse> captor = ArgumentCaptor.forClass(DeleteFileResponse.class);
		verify(obs).onNext(captor.capture());
		assertThat(captor.getValue().getSuccess()).isTrue();
	}

	@Test
	void generatePresignedUploadUrl_returns_urls() {
		when(minioService.generatePresignedUploadUrl("post-media", "a.webp", "image/webp", 600))
				.thenReturn(new MinioService.PresignResult("http://upload", "http://final"));
		StreamObserver<PresignResponse> obs = mock(StreamObserver.class);

		mediaGrpcServer.generatePresignedUploadUrl(PresignRequest.newBuilder().setBucket("post-media")
				.setFilename("a.webp").setContentType("image/webp").setExpirySeconds(600).build(), obs);

		ArgumentCaptor<PresignResponse> captor = ArgumentCaptor.forClass(PresignResponse.class);
		verify(obs).onNext(captor.capture());
		assertThat(captor.getValue().getUploadUrl()).isEqualTo("http://upload");
		assertThat(captor.getValue().getFinalUrl()).isEqualTo("http://final");
	}

	@Test
	void uploadFile_maps_illegal_argument_to_invalid_argument_status() {
		when(minioService.upload(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("bad input"));
		StreamObserver<UploadFileResponse> obs = mock(StreamObserver.class);

		mediaGrpcServer.uploadFile(UploadFileRequest.newBuilder().setBucket("x").setFilename("f")
				.setContentType("image/png").setData(com.google.protobuf.ByteString.copyFrom(new byte[]{1})).build(),
				obs);

		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(obs).onError(captor.capture());
		assertThat(captor.getValue()).isInstanceOf(StatusRuntimeException.class);
		assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
				.isEqualTo(Status.INVALID_ARGUMENT.getCode());
	}
}
