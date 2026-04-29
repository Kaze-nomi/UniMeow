package uni.gateway.dto.admin;

public record ProgramProposalDto(String id, String authorId, String universityId, String universityName,
		String universityShortName, String facultyId, String facultyName, String facultyShortName, String name,
		String shortName, String status, String createdAt, String reviewedBy, String reviewedAt) {
}
