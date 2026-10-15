package com.src.minidoodle.service.user;

import com.src.minidoodle.db.model.Calendar;
import com.src.minidoodle.db.model.User;
import com.src.minidoodle.db.repository.CalendarRepository;
import com.src.minidoodle.db.repository.UserRepository;
import com.src.minidoodle.dto.request.CreateUserRequest;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.validation.user.UserValidationService;
import com.src.minidoodle.service.system.validation.user.ValidatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists user identity and its internal calendar as one transaction.
 * API responses expose normalized user data without exposing calendar persistence details.
 */
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final CalendarRepository calendarRepository;
    private final UserValidationService userValidationService;

    /**
     * Normalizes identity fields and creates the calendar only after the user insert succeeds.
     * A duplicate email or calendar insertion failure rolls back the complete operation.
     *
     * @param request user fields that have passed request-shape validation
     * @return persisted identity with its generated identifier
     * @throws ApiException if the supplied time zone is invalid
     * @throws org.springframework.dao.DataIntegrityViolationException if the normalized email already exists
     */
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        ValidatedUser validated = userValidationService.validateAndNormalize(request);
        User user = User.builder().name(validated.name()).email(validated.email()).timeZone(validated.timeZone()).build();
        user = userRepository.saveAndFlush(user);
        calendarRepository.save(Calendar.builder().user(user).build());
        return toResponse(user);
    }

    /**
     * Looks up a persisted user without initializing its calendar.
     *
     * @param id user identifier
     * @return normalized identity and presentation time zone
     * @throws ApiException if the user does not exist
     */
    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return toResponse(userRepository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND, "User not found")));
    }

    /**
     * Maps only public identity fields, keeping JPA entities inside the service boundary.
     *
     * @param user persisted user
     * @return detached API representation
     */
    private UserResponse toResponse(User user) {
        return UserResponse.builder().id(user.getId()).name(user.getName()).email(user.getEmail()).timeZone(user.getTimeZone()).build();
    }
}
