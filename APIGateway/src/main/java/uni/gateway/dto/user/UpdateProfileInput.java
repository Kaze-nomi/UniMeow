package uni.gateway.dto.user;

public record UpdateProfileInput(String username, String name, String surname, String status, String avatarUrl,
		String coverUrl, String bio, String facultyId, String programId, Integer course,
		EducationLevelDto educationLevel, Integer graduationYear) {
}
