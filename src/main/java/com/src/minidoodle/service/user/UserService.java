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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final CalendarRepository calendarRepository;
    private final UserValidationService userValidationService;

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        var validated = userValidationService.validateAndNormalize(request);
        User user = new User();
        user.setName(validated.name());
        user.setEmail(validated.email());
        user.setTimeZone(validated.timeZone());
        user = userRepository.saveAndFlush(user);
        Calendar calendar = new Calendar();
        calendar.setUser(user);
        calendarRepository.save(calendar);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return toResponse(userRepository.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND, "User not found")));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getTimeZone());
    }
}
