package com.booking.user.controller;

import com.booking.user.client.SocEventPublisher;
import com.booking.user.dto.AuthDtos.*;
import com.booking.user.model.User;
import com.booking.user.repository.UserRepository;
import com.booking.user.security.JwtUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication endpoints. Logs successes, failures and business events
 * in a consistent format so they can later be centralised/monitored by the SOC layer.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SocEventPublisher socEventPublisher;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                           SocEventPublisher socEventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.socEventPublisher = socEventPublisher;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        log.info("event=request_received endpoint=/api/auth/register username={}", req.username());

        if (userRepository.existsByUsername(req.username())) {
            log.warn("event=registration_failed reason=username_taken username={}", req.username());
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Username already taken");
        }
        if (userRepository.existsByEmail(req.email())) {
            log.warn("event=registration_failed reason=email_taken email={}", req.email());
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Email already registered");
        }

        User user = new User();
        user.setUsername(req.username());
        user.setEmail(req.email());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole("USER");
        userRepository.save(user);

        log.info("event=user_registered username={} userId={}", user.getUsername(), user.getId());
        log.info("event=request_completed endpoint=/api/auth/register status=201");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        log.info("event=request_received endpoint=/api/auth/login username={}", req.username());

        var userOpt = userRepository.findByUsername(req.username());
        if (userOpt.isEmpty() || !passwordEncoder.matches(req.password(), userOpt.get().getPasswordHash())) {
            // SOC-relevant event: failed authentication attempt
            log.warn("event=failed_login_attempt username={}", req.username());
            socEventPublisher.publish("failed_login_attempt", "MEDIUM", req.username(), "/api/auth/login",
                    "POST", 401, "Invalid username or password", "login-endpoint");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username or password");
        }

        User user = userOpt.get();
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

        log.info("event=login_success username={} userId={}", user.getUsername(), user.getId());
        log.info("event=request_completed endpoint=/api/auth/login status=200");
        socEventPublisher.publish("login_success", "LOW", user.getUsername(), "/api/auth/login",
                "POST", 200, "User logged in successfully", "login-endpoint");

        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), user.getRole(), jwtUtil.getExpirationMs()));
    }
}
