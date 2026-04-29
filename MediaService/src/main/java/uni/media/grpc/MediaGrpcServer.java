package uni.media.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import uni.grpc.media.DeleteFileRequest;
import uni.grpc.media.DeleteFileResponse;
import uni.grpc.media.MediaServiceGrpc;
import uni.grpc.media.PresignRequest;
import uni.grpc.media.PresignResponse;
import uni.grpc.media.UploadFileRequest;
import uni.grpc.media.UploadFileResponse;
import uni.media.service.MinioService;

@GrpcService
@RequiredArgsConstructor
public class MediaGrpcServer extends MediaServiceGrpc.MediaServiceImplBase {

	private final MinioService minioService;

	@Override
	public void uploadFile(UploadFileRequest req, StreamObserver<UploadFileResponse> obs) {
		try {
			String url = minioService.upload(req.getBucket(), req.getFilename(), req.getContentType(),
					req.getData().toByteArray());

			obs.onNext(UploadFileResponse.newBuilder().setUrl(url).build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void deleteFile(DeleteFileRequest req, StreamObserver<DeleteFileResponse> obs) {
		try {
			minioService.delete(req.getBucket(), req.getFilename());
			obs.onNext(DeleteFileResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void generatePresignedUploadUrl(PresignRequest req, StreamObserver<PresignResponse> obs) {
		try {
			MinioService.PresignResult result = minioService.generatePresignedUploadUrl(req.getBucket(),
					req.getFilename(), req.getContentType(), req.getExpirySeconds());

			obs.onNext(PresignResponse.newBuilder().setUploadUrl(result.uploadUrl()).setFinalUrl(result.finalUrl())
					.build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}
}
