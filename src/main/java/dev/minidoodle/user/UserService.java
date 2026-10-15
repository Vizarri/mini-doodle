package dev.minidoodle.user;

import dev.minidoodle.common.ApiException;
import dev.minidoodle.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserService {
    private final UserRepository users;
    private final CalendarRepository calendars;
    public UserService(UserRepository users, CalendarRepository calendars) {
        this.users = users;
        this.calendars = calendars;
    }

    @Transactional
    public UserController.UserResponse create(UserController.CreateUser request) {
        try { ZoneId.of(request.timeZone()); }
        catch (DateTimeException exception) { throw ApiException.badRequest("Unknown time zone"); }
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setName(request.name().strip());
        user.setEmail(request.email().strip().toLowerCase(Locale.ROOT));
        user.setTimeZone(request.timeZone());
        users.saveAndFlush(user);
        CalendarEntity calendar = new CalendarEntity();
        calendar.setId(UUID.randomUUID());
        calendar.setUserId(user.getId());
        calendars.save(calendar);
        return response(user);
    }

    @Transactional(readOnly = true)
    public UserController.UserResponse get(UUID id) {
        return response(users.findById(id).orElseThrow(() -> ApiException.notFound("User not found")));
    }

    private UserController.UserResponse response(UserEntity user) {
        return new UserController.UserResponse(user.getId(), user.getName(), user.getEmail(), user.getTimeZone());
    }
}
