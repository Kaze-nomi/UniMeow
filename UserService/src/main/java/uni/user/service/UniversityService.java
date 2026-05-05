package uni.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.user.entity.University;
import uni.user.entity.UniversityFaculty;
import uni.user.entity.UniversityProgram;
import uni.user.entity.UniversityTopic;
import uni.user.repository.UniversityFacultyRepository;
import uni.user.repository.UniversityProgramRepository;
import uni.user.repository.UniversityRepository;
import uni.user.repository.UniversityTopicRepository;
import uni.user.repository.UserRepository;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UniversityService {

	private final UniversityRepository universityRepository;
	private final UniversityFacultyRepository facultyRepository;
	private final UniversityProgramRepository programRepository;
	private final UniversityTopicRepository topicRepository;
	private final UserRepository userRepository;

	@Transactional(readOnly = true)
	public List<University> listUniversities() {
		return universityRepository.findAll().stream()
				.sorted(Comparator.comparing(University::getName, String.CASE_INSENSITIVE_ORDER)).toList();
	}

	@Transactional(readOnly = true)
	public List<UniversityFaculty> listFaculties(Long universityId) {
		if (universityId == null || universityId <= 0) {
			throw new IllegalArgumentException("universityId must be positive");
		}

		if (!universityRepository.existsById(universityId)) {
			throw new IllegalArgumentException("University not found: " + universityId);
		}

		return facultyRepository.findByUniversityId(universityId).stream()
				.sorted(Comparator.comparing(UniversityFaculty::getName, String.CASE_INSENSITIVE_ORDER)).toList();
	}

	@Transactional(readOnly = true)
	public List<UniversityProgram> listPrograms(Long facultyId) {
		if (facultyId == null || facultyId <= 0) {
			throw new IllegalArgumentException("facultyId must be positive");
		}
		if (!facultyRepository.existsById(facultyId)) {
			throw new IllegalArgumentException("Faculty not found: " + facultyId);
		}
		return programRepository.findByFacultyId(facultyId).stream()
				.sorted(Comparator.comparing(UniversityProgram::getName, String.CASE_INSENSITIVE_ORDER)).toList();
	}

	@Transactional
	public ValidateResult validateTopicForUniversity(Long universityId, Long topicId) {
		UniversityTopic topic = topicRepository.findById(topicId)
				.orElseThrow(() -> new IllegalArgumentException("Topic not found: " + topicId));

		if (topic.getParent() != null) {
			Long parentId = topic.getParent().getId();
			UniversityTopic parent = topicRepository.findById(parentId)
					.orElseThrow(() -> new IllegalArgumentException("Parent topic not found"));
			if (parent.getUniversity().getId().equals(universityId)) {
				return new ValidateResult(true, parent.getId());
			}
		}

		if (topic.getUniversity().getId().equals(universityId)) {
			return new ValidateResult(true, null);
		}

		return new ValidateResult(false, null);
	}

	public Long getGeneralTopicId(Long universityId) {
		return topicRepository.findByUniversityIdAndSlugAndParentIsNull(universityId, "general")
				.map(UniversityTopic::getId).orElseThrow(
						() -> new IllegalArgumentException("General topic not found for university: " + universityId));
	}

	public PostTargetResult resolvePostTarget(String authorId, Long requestedTopicId) {
		uni.user.entity.User author = userRepository.findById(UUID.fromString(authorId))
				.orElseThrow(() -> new IllegalArgumentException("Author not found: " + authorId));

		if (requestedTopicId != null) {
			UniversityTopic topic = topicRepository.findById(requestedTopicId)
					.orElseThrow(() -> new IllegalArgumentException("Topic not found: " + requestedTopicId));
			Long parentTopicId = topic.getParent() != null ? topic.getParent().getId() : topic.getId();
			return PostTargetResult.success(topic.getUniversity().getId(),
					topic.getFaculty() != null ? topic.getFaculty().getId() : null, topic.getId(), topic.getId(),
					parentTopicId);
		}

		if (author.getUniversity() != null) {
			Long universityId = author.getUniversity().getId();
			Long facultyId = author.getFaculty() != null ? author.getFaculty().getId() : null;
			Long programId = author.getProgram() != null ? author.getProgram().getId() : null;
			return PostTargetResult.success(universityId, facultyId, programId, programId, programId);
		}

		return PostTargetResult.global();
	}

	public record ValidateResult(boolean success, Long parentTopicId) {
	}

	public record PostTargetResult(boolean success, Long universityId, Long facultyId, Long programId, Long topicId,
			Long parentTopicId, String error) {
		public static PostTargetResult success(Long universityId, Long facultyId, Long programId, Long topicId,
				Long parentTopicId) {
			return new PostTargetResult(true, universityId, facultyId, programId, topicId, parentTopicId, "");
		}

		public static PostTargetResult error(String error) {
			return new PostTargetResult(false, null, null, null, null, null, error);
		}

		public static PostTargetResult global() {
			return new PostTargetResult(true, null, null, null, null, null, "");
		}
	}
}
