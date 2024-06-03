package com.onticket.user.controller;


import com.onticket.user.domain.RefreshToken;
import com.onticket.user.jwt.JwtUtil;
import com.onticket.user.service.RefreshTokenService;
import com.onticket.user.service.UserSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        System.out.println(username+password);
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        String jwt = jwtUtil.generateAccessToken(username);
        String refreshToken = jwtUtil.generateRefreshToken(username);

        refreshTokenService.saveRefreshToken(refreshToken, username);

        return ResponseEntity.ok().body(Map.of("token", jwt, "refreshToken", refreshToken));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshAccessToken(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");

        if (jwtUtil.validateToken(refreshToken)) {
            String username = jwtUtil.getClaimsFromToken(refreshToken).getSubject();
            Optional<RefreshToken> storedRefreshToken = refreshTokenService.findByToken(refreshToken);

            if (storedRefreshToken.isPresent() && storedRefreshToken.get().getUsername().equals(username)) {
                String newAccessToken = jwtUtil.generateAccessToken(username);
                return ResponseEntity.ok().body(Map.of("token", newAccessToken));
            }
        }

        return ResponseEntity.status(403).body("Invalid refresh token");
    }
}