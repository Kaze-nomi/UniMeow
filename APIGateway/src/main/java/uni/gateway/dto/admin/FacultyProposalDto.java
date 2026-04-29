package uni.gateway.dto.admin;

public record FacultyProposalDto(String id, String authorId, String universityId, String universityName,
		String universityShortName, String name, String shortName, String status, String createdAt, String reviewedBy,
		String reviewedAt) {
}
