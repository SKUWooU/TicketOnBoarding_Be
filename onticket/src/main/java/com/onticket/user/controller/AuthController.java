package com.onticket.user.controller;


import com.onticket.user.domain.RefreshToken;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.form.UserLoginForm;
import com.onticket.user.jwt.JwtUtil;
import com.onticket.user.repository.UserRepository;
import com.onticket.user.service.RefreshTokenService;
import com.onticket.user.service.UserSecurityService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;


    @Value("${naver.client.id}")
    private String clientId;

    @Value("${naver.client.secret}")
    private String clientSecret;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String googleClientSecret;

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody UserLoginForm userLoginForm, BindingResult bindingResult, HttpServletResponse response) {
        try {

            if (bindingResult.hasErrors()) {
                //로그인폼에 있는 에러메세지 받아옴-> 빈값이면 메세지 출력
                String errorMessage = bindingResult.getFieldError().getDefaultMessage();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMessage);
            }
            String username = userLoginForm.getUsername();
            String password = userLoginForm.getPassword();

            //사용자 검증
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));


            SecurityContextHolder.getContext().setAuthentication(authentication);

            //토큰생성
            String accessToken = jwtUtil.generateAccessToken(username);
            String refreshToken = jwtUtil.generateRefreshToken(username);

            refreshTokenService.saveRefreshToken(refreshToken, username);

            // HttpOnly 쿠키로 토큰 설정
            Cookie accessTokenCookie = new Cookie("accessToken", accessToken);
            accessTokenCookie.setHttpOnly(true);
            //모든경로
            accessTokenCookie.setPath("/");
            accessTokenCookie.setMaxAge(60 * 60); // 15분

            Cookie refreshTokenCookie = new Cookie("refreshToken", refreshToken);
            refreshTokenCookie.setHttpOnly(true);
            refreshTokenCookie.setPath("/");
            refreshTokenCookie.setMaxAge(7 * 24 * 60 * 60); // 7일

            response.addCookie(accessTokenCookie);
            response.addCookie(refreshTokenCookie);
            return ResponseEntity.ok().body(Map.of("message", "로그인 성공"));
        }catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("아이디 또는 비밀번호가 올바르지 않습니다.");
        }catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("로그인에 실패했습니다.");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        Cookie accessTokenCookie = new Cookie("accessToken", null);
        accessTokenCookie.setHttpOnly(true);
        accessTokenCookie.setPath("/");
        accessTokenCookie.setMaxAge(0);  // 쿠키를 즉시 삭제

        Cookie refreshTokenCookie = new Cookie("refreshToken", null);
        refreshTokenCookie.setHttpOnly(true);
        refreshTokenCookie.setPath("/");
        refreshTokenCookie.setMaxAge(0);  // 쿠키를 즉시 삭제

        response.addCookie(accessTokenCookie);
        response.addCookie(refreshTokenCookie);

        return ResponseEntity.ok("로그아웃 성공");
    }

    @GetMapping("/valid")
    public ResponseEntity<?> validateToken(@CookieValue(value = "accessToken", required = false) String token) {
        if (token != null && jwtUtil.validateToken(token)) {
            return ResponseEntity.ok(Map.of("valid", true));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("valid", false));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("refreshToken".equals(cookie.getName())) {
                    refreshToken = cookie.getValue();
                }
            }
        }

        if (refreshToken != null && jwtUtil.validateToken(refreshToken)) {
            String username = jwtUtil.getUsernameFromToken(refreshToken);
            String newAccessToken = jwtUtil.generateAccessToken(username);

            Cookie accessTokenCookie = new Cookie("accessToken", newAccessToken);
            accessTokenCookie.setHttpOnly(true);
            accessTokenCookie.setPath("/");
            accessTokenCookie.setMaxAge(15 * 60); // 15분
            response.addCookie(accessTokenCookie);

            return ResponseEntity.ok().body(Map.of("message", "토큰발급성공"));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("토큰이 만료되었습니다.");
        }
    }

    @PostMapping("/naver")
    public ResponseEntity<?> naverLogin(@RequestBody Map<String, String> requestBody,HttpServletResponse response) {
        try {
            String code = requestBody.get("code");
            System.out.println(code);
            String state = requestBody.get("state");
            System.out.println(state);
            String tokenUrl = "https://nid.naver.com/oauth2.0/token?grant_type=authorization_code" +
                    "&client_id=" + clientId +
                    "&client_secret=" + clientSecret +
                    "&code=" + code +
                    "&state=" + state;

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<Map> tokenResponse = restTemplate.exchange(tokenUrl, HttpMethod.GET, null, Map.class);

            String accessToken = (String) tokenResponse.getBody().get("access_token");

            String profileUrl = "https://openapi.naver.com/v1/nid/me";
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> profileResponse = restTemplate.exchange(profileUrl, HttpMethod.GET, entity, Map.class);

            Map<String, Object> profile = (Map<String, Object>) profileResponse.getBody().get("response");
            String naverId = (String) profile.get("id");
            System.out.println(naverId);
            String email = (String) profile.get("email");
            String name = (String) profile.get("name");

            SiteUser user = userRepository.findByNaverid(naverId);
            if (user == null) {
                // 신규 사용자일 경우 사용자 정보를 DB에 저장
                user = new SiteUser();
                user.setUsername(UUID.randomUUID().toString()); // 애플리케이션의 사용자 ID 생성
                user.setEmail(email);
                user.setNickname(name);
                user.setNaverid(naverId);
                user.setCode(2);
                userRepository.save(user);
            }


            System.out.println(user.getUsername());
            String token = jwtUtil.generateAccessToken(user.getUsername());
            String refreshToken = jwtUtil.generateRefreshToken(user.getUsername());

            refreshTokenService.saveRefreshToken(refreshToken, user.getUsername());

            // HttpOnly 쿠키로 토큰 설정
            Cookie accessTokenCookie = new Cookie("accessToken", token);
            accessTokenCookie.setHttpOnly(true);
            //모든경로
            accessTokenCookie.setPath("/");
            accessTokenCookie.setMaxAge(60 * 60); // 15분

            Cookie refreshTokenCookie = new Cookie("refreshToken", refreshToken);
            refreshTokenCookie.setHttpOnly(true);
            refreshTokenCookie.setPath("/");
            refreshTokenCookie.setMaxAge(7 * 24 * 60 * 60); // 7일

            response.addCookie(accessTokenCookie);
            response.addCookie(refreshTokenCookie);
            return ResponseEntity.ok().body(Map.of("message", "로그인 성공"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("다시 시도하세요");
        }
    }

    @GetMapping("/user")
    public ResponseEntity<?> getUser(@CookieValue(value = "accessToken", required = false) String token) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user == null) {
                return ResponseEntity.badRequest().body("일치하는 사용자가 없습니다.");
            }
            Map<String,Object> map = new HashMap<>();
            String nickname = user.getNickname();

            map.put("code",user.getCode());
            map.put("nickName",nickname);
            return ResponseEntity.ok(map);
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("토큰이 잘못되었습니다.");
    }

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, ?> requestBody,HttpServletResponse response) {
        try {
            Map<String, Object> user = (Map<String, Object>) requestBody.get("user");
            System.out.println(user);
            String email = (String) user.get("email");
            String name = (String) user.get("name");
            SiteUser siteUser = userRepository.findByGoogleemail(email);
            if (siteUser == null) {
                // 신규 사용자일 경우 사용자 정보를 DB에 저장
                siteUser = new SiteUser();
                siteUser.setUsername(UUID.randomUUID().toString()); // 애플리케이션의 사용자 ID 생성
                siteUser.setGoogleemail(email);
                siteUser.setNickname(name);
                siteUser.setCode(2);
                userRepository.save(siteUser);
            }


            String token = jwtUtil.generateAccessToken(siteUser.getUsername());
            String refreshToken = jwtUtil.generateRefreshToken(siteUser.getUsername());

            refreshTokenService.saveRefreshToken(refreshToken, siteUser.getUsername());

            // HttpOnly 쿠키로 토큰 설정
            Cookie accessTokenCookie = new Cookie("accessToken", token);
            accessTokenCookie.setHttpOnly(true);
            //모든경로
            accessTokenCookie.setPath("/");
            accessTokenCookie.setMaxAge(60 * 60); // 15분

            Cookie refreshTokenCookie = new Cookie("refreshToken", refreshToken);
            refreshTokenCookie.setHttpOnly(true);
            refreshTokenCookie.setPath("/");
            refreshTokenCookie.setMaxAge(7 * 24 * 60 * 60); // 7일

            response.addCookie(accessTokenCookie);
            response.addCookie(refreshTokenCookie);

            return ResponseEntity.ok("로그인 성공");
        } catch (Exception e){
            return ResponseEntity.badRequest().body("로그인 실패");
        }
    }
}