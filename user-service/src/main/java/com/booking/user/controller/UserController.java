package com.booking.user.controller;

import com.booking.user.dto.AuthDtos.UserResponse;
import com.booking.user.model.User;
import com.booking.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read endpoints for user data. In production these would be restricted to
 * ADMIN role (enforced at the Gateway); kept open here for demo simplicity.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<UserResponse> getAll() {
        log.info("event=request_received endpoint=/api/users method=GET");
        return userRepository.findAll().stream()
                .map(u -> new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getRole()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        log.info("event=request_received endpoint=/api/users/{} method=GET", id);
        return userRepository.findById(id)
                .map(u -> ResponseEntity.ok(new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getRole())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-username/{username}")
    public ResponseEntity<UserResponse> getByUsername(@PathVariable String username) {
        return userRepository.findByUsername(username)
                .map(u -> ResponseEntity.ok(new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getRole())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
