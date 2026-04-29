package uni.media.service;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/svg+xml", "image/png", "image/jpeg",
			"image/webp");

	private final MinioClient minioClient;

	@Value("${app.minio.public-base-url}")
	private String publicBaseUrl;

	@Value("${app.minio.max-file-size.university-icons-bytes:10485760}")
	private long maxUniversityIconSize;

	@Value("${app.minio.max-file-size.user-avatars-bytes:5242880}")
	private long maxUserAvatarSize;

	@Value("${app.minio.max-file-size.user-banners-bytes:10485760}")
	private long maxUserBannerSize;

	@Value("${app.minio.max-file-size.post-media-bytes:52428800}")
	private long maxPostMediaSize;

	@PostConstruct
	public void ensureBuckets() {
		createBucketIfMissing("university-icons");
		createBucketIfMissing("user-avatars");
		createBucketIfMissing("user-banners");
		createBucketIfMissing("post-media");
	}

	public String upload(String bucket, String filename, String contentType, byte[] data) {
		validateBucket(bucket);
		validateContentType(contentType);
		validateSize(bucket, data.length);

		String normalizedFileName = normalizeFilename(filename);

		try {
			minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(normalizedFileName)
					.stream(new ByteArrayInputStream(data), data.length, -1).contentType(contentType).build());
		} catch (Exception e) {
			throw new IllegalStateException("Failed to upload file", e);
		}

		log.info("Uploaded file {}/{}", bucket, normalizedFileName);
		return publicObjectUrl(bucket, normalizedFileName);
	}

	public void delete(String bucket, String filename) {
		validateBucket(bucket);
		String normalizedFileName = normalizeFilename(filename);

		try {
			minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(normalizedFileName).build());
		} catch (Exception e) {
			throw new IllegalStateException("Failed to delete file", e);
		}
		log.info("Deleted file {}/{}", bucket, normalizedFileName);
	}

	public PresignResult generatePresignedUploadUrl(String bucket, String filename, String contentType,
			int expirySeconds) {
		validateBucket(bucket);
		validateContentType(contentType);

		String normalizedFileName = normalizeFilename(filename);
		int effectiveExpiry = Math.max(60, Math.min(expirySeconds, 86400));

		try {
			String uploadUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT)
					.bucket(bucket).object(normalizedFileName).expiry(effectiveExpiry).build());
			return new PresignResult(uploadUrl, publicObjectUrl(bucket, normalizedFileName));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to generate presigned URL", e);
		}
	}

	private void validateBucket(String bucket) {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("bucket is required");
		}
		if (!Set.of("university-icons", "user-avatars", "user-banners", "post-media").contains(bucket)) {
			throw new IllegalArgumentException("Unsupported bucket: " + bucket);
		}
	}

	private void validateContentType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			throw new IllegalArgumentException("contentType is required");
		}
		String normalized = contentType.toLowerCase(Locale.ROOT).trim();
		if (!ALLOWED_CONTENT_TYPES.contains(normalized)) {
			throw new IllegalArgumentException("Unsupported contentType: " + contentType);
		}
	}

	private void validateSize(String bucket, int size) {
		long max = switch (bucket) {
			case "university-icons" -> maxUniversityIconSize;
			case "user-avatars" -> maxUserAvatarSize;
			case "user-banners" -> maxUserBannerSize;
			case "post-media" -> maxPostMediaSize;
			default -> 0L;
		};

		if (size <= 0) {
			throw new IllegalArgumentException("File is empty");
		}
		if (size > max) {
			throw new IllegalArgumentException("File exceeds max size for bucket " + bucket);
		}
	}

	private String normalizeFilename(String filename) {
		if (filename == null || filename.isBlank()) {
			throw new IllegalArgumentException("filename is required");
		}

		String normalized = filename.toLowerCase(Locale.ROOT).replace("..", "").replace("/", "-").replace("\\", "-")
				.replaceAll("[^a-z0-9._-]", "-").replaceAll("-+", "-").replaceAll("(^-|-$)", "");

		if (normalized.isBlank()) {
			throw new IllegalArgumentException("Invalid filename");
		}
		return normalized;
	}

	private String publicObjectUrl(String bucket, String objectName) {
		String base = publicBaseUrl.endsWith("/")
				? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
				: publicBaseUrl;
		return base + "/" + bucket + "/" + objectName;
	}

	private void createBucketIfMissing(String bucketName) {
		try {
			boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
			if (!exists) {
				minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
				log.info("Created MinIO bucket: {}", bucketName);
			}
			minioClient.setBucketPolicy(
					SetBucketPolicyArgs.builder().bucket(bucketName).config(publicReadPolicy(bucketName)).build());
		} catch (Exception e) {
			throw new IllegalStateException("Failed to ensure bucket: " + bucketName, e);
		}
	}

	private static String publicReadPolicy(String bucketName) {
		return """
				{
				  "Version": "2012-10-17",
				  "Statement": [
				    {
				      "Effect": "Allow",
				      "Principal": "*",
				      "Action": ["s3:GetObject"],
				      "Resource": ["arn:aws:s3:::%s/*"]
				    }
				  ]
				}
				""".replace("\n", "%n").formatted(bucketName);
	}

	public record PresignResult(String uploadUrl, String finalUrl) {
	}
}
