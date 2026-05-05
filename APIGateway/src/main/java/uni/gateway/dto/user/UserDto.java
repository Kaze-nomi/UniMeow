package uni.gateway.dto.user;

import lombok.Builder;

@Builder
public record UserDto(String id, String emailGoogle, String username, String name, String surname,
		String emailUniversity, String avatarUrl, String coverUrl, String status, String bio, boolean isStudentVerified,
		boolean isEmployeeVerified, String createdAt, UniversityDto university, FacultyDto faculty, ProgramDto program,
		Integer course, EducationLevelDto educationLevel, Integer graduationYear, boolean isAdmin, boolean isBanned,
		String bannedUntil, String banReason) {
}
