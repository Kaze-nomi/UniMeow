package uni.gateway.controller;

import org.springframework.stereotype.Component;
import uni.gateway.dto.user.EducationLevelDto;
import uni.gateway.dto.user.FacultyDto;
import uni.gateway.dto.user.ProgramDto;
import uni.gateway.dto.user.UniversityDto;
import uni.gateway.dto.user.UserDto;
import uni.grpc.user.EducationLevel;
import uni.grpc.user.UserResponse;

@Component
public class UserMapper {

	public UserDto toDto(UserResponse r) {
		return UserDto.builder().id(r.getId()).emailGoogle(r.getEmailGoogle()).username(r.getUsername())
				.name(r.getName()).surname(r.getSurname().isEmpty() ? null : r.getSurname())
				.emailUniversity(r.getEmailUniversity().isEmpty() ? null : r.getEmailUniversity())
				.avatarUrl(r.getAvatarUrl().isEmpty() ? null : r.getAvatarUrl())
				.coverUrl(r.hasCoverUrl() ? r.getCoverUrl() : null).status(
						r.getStatus().isEmpty() ? null : r.getStatus())
				.bio(r.hasBio() ? r.getBio() : null).isStudentVerified(
						r.getIsStudentVerified())
				.isEmployeeVerified(r.getIsEmployeeVerified()).createdAt(
						r.getCreatedAt())
				.university(
						r.hasUniversity()
								? UniversityDto.builder().id(Long.toString(r.getUniversity().getId()))
										.name(r.getUniversity().getName()).shortName(r.getUniversity().getShortName())
										.subdomain(r.getUniversity().getSubdomain().isEmpty()
												? null
												: r.getUniversity().getSubdomain())
										.iconUrl(r.getUniversity().getIconUrl().isEmpty()
												? null
												: r.getUniversity().getIconUrl())
										.build()
								: null)
				.faculty(r.hasFaculty()
						? FacultyDto.builder().id(Long.toString(r.getFaculty().getId())).name(r.getFaculty().getName())
								.shortName(r.getFaculty().getShortName()).build()
						: null)
				.program(r.hasProgram()
						? ProgramDto.builder().id(Long.toString(r.getProgram().getId()))
								.facultyId(Long.toString(r.getProgram().getFacultyId())).name(r.getProgram().getName())
								.shortName(r.getProgram().getShortName()).build()
						: null)
				.course(r.hasCourse() ? r.getCourse() : null)
				.educationLevel(r.hasEducationLevel() ? mapEducationLevel(r.getEducationLevel()) : null)
				.graduationYear(r.hasGraduationYear() ? r.getGraduationYear() : null).isAdmin(r.getIsAdmin())
				.isBanned(r.getIsBanned()).bannedUntil(r.hasBannedUntil() ? r.getBannedUntil() : null)
				.banReason(r.hasBanReason() ? r.getBanReason() : null).build();
	}

	private static EducationLevelDto mapEducationLevel(EducationLevel level) {
		return switch (level) {
			case BACHELOR -> EducationLevelDto.BACHELOR;
			case MASTER -> EducationLevelDto.MASTER;
			case PHD -> EducationLevelDto.PHD;
			case SPECIALIST -> EducationLevelDto.SPECIALIST;
			default -> null;
		};
	}
}
