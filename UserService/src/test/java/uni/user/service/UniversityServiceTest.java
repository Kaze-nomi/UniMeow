package uni.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.user.entity.University;
import uni.user.entity.UniversityTopic;
import uni.user.grpc.MediaGrpcClient;
import uni.user.repository.UniversityRepository;
import uni.user.repository.UniversityTopicRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UniversityServiceTest {

	@Mock
	UniversityRepository universityRepository;

	@Mock
	MediaGrpcClient mediaGrpcClient;

	@Mock
	UniversityTopicRepository topicRepository;

	@InjectMocks
	UniversityService universityService;

	@Test
	void uploadUniversityIcon_uploads_to_media_and_saves_url() {
		University university = University.builder().id(1L).name("Higher School of Economics").shortName("HSE FKN")
				.createdAt(LocalDateTime.now()).build();
		byte[] data = new byte[]{1, 2, 3};

		when(universityRepository.findById(1L)).thenReturn(Optional.of(university));
		when(mediaGrpcClient.uploadFile("university-icons", "hse-fkn.png", "image/png", data))
				.thenReturn("https://cdn/university-icons/hse-fkn.png");

		String url = universityService.uploadUniversityIcon(1L, data, "image/png");

		assertThat(url).isEqualTo("https://cdn/university-icons/hse-fkn.png");
		ArgumentCaptor<University> saved = ArgumentCaptor.forClass(University.class);
		verify(universityRepository).save(saved.capture());
		assertThat(saved.getValue().getIconUrl()).isEqualTo(url);
	}

	@Test
	void uploadUniversityIcon_rejects_unsupported_content_type() {
		assertThatThrownBy(() -> universityService.uploadUniversityIcon(1L, new byte[]{1}, "text/plain"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported content type");
	}

	@Test
	void validateTopicForUniversity_returns_true_when_topic_belongs_to_university() {
		University uni = university(10L);
		UniversityTopic topic = UniversityTopic.builder().id(100L).university(uni).slug("general").name("General")
				.isSystem(true).build();

		when(topicRepository.findById(100L)).thenReturn(Optional.of(topic));

		UniversityService.ValidateResult result = universityService.validateTopicForUniversity(10L, 100L);

		assertThat(result.success()).isTrue();
		assertThat(result.parentTopicId()).isNull();
	}

	@Test
	void validateTopicForUniversity_returns_parent_id_for_valid_subtopic() {
		University parentUni = university(10L);
		University otherUni = university(11L);

		UniversityTopic parent = UniversityTopic.builder().id(200L).university(parentUni).slug("fkn").name("FKN")
				.isSystem(false).build();

		UniversityTopic child = UniversityTopic.builder().id(201L).university(otherUni).parent(parent).slug("pi")
				.name("PI").isSystem(false).build();

		when(topicRepository.findById(201L)).thenReturn(Optional.of(child));
		when(topicRepository.findById(200L)).thenReturn(Optional.of(parent));

		UniversityService.ValidateResult result = universityService.validateTopicForUniversity(10L, 201L);

		assertThat(result.success()).isTrue();
		assertThat(result.parentTopicId()).isEqualTo(200L);
	}

	@Test
	void validateTopicForUniversity_returns_false_for_foreign_topic() {
		University uni = university(99L);
		UniversityTopic topic = UniversityTopic.builder().id(300L).university(uni).slug("foreign").name("Foreign")
				.isSystem(false).build();

		when(topicRepository.findById(300L)).thenReturn(Optional.of(topic));

		UniversityService.ValidateResult result = universityService.validateTopicForUniversity(10L, 300L);

		assertThat(result.success()).isFalse();
		assertThat(result.parentTopicId()).isNull();
	}

	@Test
	void getGeneralTopicId_returns_general_topic_for_university() {
		UniversityTopic general = UniversityTopic.builder().id(500L).university(university(10L)).slug("general")
				.name("General").isSystem(true).build();
		when(topicRepository.findByUniversityIdAndSlugAndParentIsNull(10L, "general")).thenReturn(Optional.of(general));

		Long topicId = universityService.getGeneralTopicId(10L);

		assertThat(topicId).isEqualTo(500L);
	}

	private static University university(Long id) {
		return University.builder().id(id).name("University " + id).shortName("U" + id).createdAt(LocalDateTime.now())
				.build();
	}
}
