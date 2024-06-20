package com.onticket.admin;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.Reservation;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.service.ConcertService;
import com.onticket.concert.service.SeatReservationService;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.jwt.JwtUtil;
import com.onticket.user.repository.UserRepository;
import com.onticket.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;


@RequiredArgsConstructor
@RestController
public class AdminController {
    private final JwtUtil jwtUtil;
    private final SeatReservationService seatReservationService;
    private final UserRepository userRepository;
    private final ConcertService concertService;
    private final UserService userService;
    private final ReservationRepository reservationRepository;

    //공연조회
    @GetMapping("/admin/concerts")
    public ResponseEntity<?> getAllConcert(@CookieValue(value = "accessToken", required = false) String token){
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user.getCode()!=3){
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
            }
            return ResponseEntity.ok(concertService.getAllConcert());
        } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    //MD's Pick 조회
    @GetMapping("/admin/mdspick")
    public ResponseEntity<?> getAllMdspick(@CookieValue(value = "accessToken", required = false) String token){
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user.getCode()!=3){
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
            }
            return ResponseEntity.ok(concertService.getAllOnTicketPick());
        } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    //MD's pick 삭제
    @PostMapping("/admin/mdspick/{concertId}")
    public ResponseEntity<?> setOnTicketPick(@CookieValue(value = "accessToken", required = false) String token, @PathVariable("concertId") String concertId, @RequestBody Map<String,Integer> requestBody){
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user.getCode()!=3){
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
            }
            int pick=requestBody.get("pick");
            concertService.setOnTicketPick(concertId,pick);
            return ResponseEntity.ok("성공적으로 등록되었습니다.");
        } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    //유저조회
    @GetMapping("/admin/users")
    public ResponseEntity<?> getAllUsers(@CookieValue(value = "accessToken", required = false) String token){
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user.getCode()!=3){
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
            }
            return ResponseEntity.ok(userService.getAllUsers());
        } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    //취소신청내역 조회
    @GetMapping("/admin/cancel")
    public ResponseEntity<?> cancel(@CookieValue(value = "accessToken", required = false) String token){
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user.getCode()!=3){
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
            }
            return ResponseEntity.ok(seatReservationService.getCancelList());
        } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    @PostMapping("/admin/cancel/{reservationId}")
    public ResponseEntity<?> cancelReservation(@CookieValue(value = "accessToken", required = false) String token, @PathVariable("reservationId") Long reservationId) {
        try {
            if (token != null && jwtUtil.validateToken(token)) {
                String username = jwtUtil.getUsernameFromToken(token);
                SiteUser user = userRepository.findByUsername(username);
                if (user.getCode() != 3) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
                }
                seatReservationService.cancelReservation(reservationId);
                return ResponseEntity.ok("취소처리가 완료되었습니다.");
            } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("취소처리에 실패했습니다.");
        }
    }

    @PostMapping("/admin/users")
    public ResponseEntity<?> addRole(@CookieValue(value = "accessToken", required = false) String token, @RequestBody Map<String,?> requestBody){
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user.getCode() != 3) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
            }
            String userid= (String) requestBody.get("username");
            SiteUser siteuser=userRepository.findByUsername(userid);
            siteuser.setCode(3);
            userRepository.save(siteuser);
            return ResponseEntity.ok("관리자로 변경되었습니다.");
        } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    @GetMapping("/admin/users/{userId}/reservation")
    public ResponseEntity<?> getReservations(@CookieValue(value = "accessToken", required = false) String token,@PathVariable("userId") String userId){
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user.getCode() != 3) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
            }
            Optional<List<Reservation>> optionalReservationList= reservationRepository.findByUsername(userId);
            return ResponseEntity.ok(optionalReservationList.orElse(null));
        }else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    @PostMapping("/admin/delete/{concertId}")
    public ResponseEntity<?> deleteAboutConcert(@CookieValue(value = "accessToken", required = false) String token, @PathVariable("concertId") String concertId){
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user.getCode() != 3) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
            }
            concertService.delete(concertId);
            return ResponseEntity.ok("삭제가 완료되었습니다.");
        }else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    @PostMapping("/admin/users/delete/{userId}")
    public ResponseEntity<?> deleteUser(@CookieValue(value="acceeToken", required = false)String token, @PathVariable("userId")String userId){
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SiteUser user = userRepository.findByUsername(username);
            if (user.getCode() != 3) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("관리자 권한이 없습니다.");
            }
            userService.delete(userId);
            return ResponseEntity.ok("회원을 삭제했습니다.");
        }else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }
}
