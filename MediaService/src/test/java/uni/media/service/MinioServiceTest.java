package uni.media.service;

import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinioServiceTest {

	@Mock
	MinioClient minioClient;

	@InjectMocks
	MinioService minioService;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(minioService, "publicBaseUrl", "http://localhost:9000");
		ReflectionTestUtils.setField(minioService, "maxUniversityIconSize", 30_000L);
		ReflectionTestUtils.setField(minioService, "maxUserAvatarSize", 10L);
		ReflectionTestUtils.setField(minioService, "maxUserBannerSize", 10L);
		ReflectionTestUtils.setField(minioService, "maxPostMediaSize", 10L);
	}

	@Test
	void upload_normalizes_filename_and_returns_public_url() throws Exception {
		byte[] data = new byte[]{1, 2, 3};
		when(minioClient.putObject(any())).thenReturn(null);

		String url = minioService.upload("university-icons", "My Logo.PNG", "image/png", data);

		assertThat(url).isEqualTo("http://localhost:9000/university-icons/my-logo.png");
		verify(minioClient).putObject(any());
	}

	@Test
	void upload_rejects_unsupported_content_type() {
		assertThatThrownBy(() -> minioService.upload("university-icons", "icon.png", "text/plain", new byte[]{1}))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported contentType");
	}

	@Test
	void upload_accepts_video_for_post_media() throws Exception {
		when(minioClient.putObject(any())).thenReturn(null);

		String url = minioService.upload("post-media", "clip.mp4", "video/mp4", new byte[]{1, 2, 3});

		assertThat(url).isEqualTo("http://localhost:9000/post-media/clip.mp4");
		verify(minioClient).putObject(any());
	}

	@Test
	void upload_accepts_gif_image() throws Exception {
		when(minioClient.putObject(any())).thenReturn(null);

		String url = minioService.upload("post-media", "reaction.gif", "image/gif", new byte[]{1, 2, 3});

		assertThat(url).isEqualTo("http://localhost:9000/post-media/reaction.gif");
		verify(minioClient).putObject(any());
	}

	@Test
	void upload_rejects_video_for_avatar_bucket() {
		assertThatThrownBy(() -> minioService.upload("user-avatars", "clip.mp4", "video/mp4", new byte[]{1}))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported contentType");
	}

	@Test
	void upload_rejects_too_large_file() {
		assertThatThrownBy(() -> minioService.upload("user-avatars", "a.png", "image/png", new byte[11]))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds max size");
	}

	@Test
	void generatePresignedUploadUrl_clamps_expiry_and_returns_final_url() throws Exception {
		when(minioClient.getPresignedObjectUrl(any())).thenReturn("http://upload-url");

		MinioService.PresignResult result = minioService.generatePresignedUploadUrl("post-media", "photo.webp",
				"image/webp", 1);

		assertThat(result.uploadUrl()).isEqualTo("http://upload-url");
		assertThat(result.finalUrl()).isEqualTo("http://localhost:9000/post-media/photo.webp");
		verify(minioClient).getPresignedObjectUrl(any());
	}

	@Test
	void ensureBuckets_sets_public_read_policy_for_all_buckets() throws Exception {
		when(minioClient.bucketExists(any())).thenReturn(true);

		minioService.ensureBuckets();

		verify(minioClient, times(4)).setBucketPolicy(any());
	}
}
