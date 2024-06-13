package com.onticket.concert.controller;

import com.onticket.concert.domain.Review;
import com.onticket.concert.service.ReviewService;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.jwt.JwtUtil;
import com.onticket.user.repository.UserRepository;
import io.jsonwebtoken.Jwt;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
public class ReviewController {

    private final ReviewService reviewService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    //리뷰페이지
    @GetMapping("/main/detail/{concertId}/reviews")
    public ResponseEntity<List<Review>> getReviews(@PathVariable String concertId) {
        List<Review> reviewList = reviewService.getReviews(concertId);
        return ResponseEntity.ok(reviewList);
    }

    //리뷰포스트
    @PostMapping("/main/detail/{concertId}/register/review")
    public ResponseEntity<?> registerReview(@CookieValue(value = "accessToken", required = false) String token, @PathVariable("concertId") String concertId, @RequestBody Map<String,?> requestBody) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username=jwtUtil.getUsernameFromToken(token);
            String content= (String) requestBody.get("content");
            Integer starCount= (Integer) requestBody.get("starCount");
            float parseStarCount=  starCount.floatValue();
            reviewService.create(concertId,username,content,parseStarCount);
            return ResponseEntity.ok().body("리뷰가 등록되었습니다.");
        } else {
            return ResponseEntity.badRequest().body("로그인이 필요한 서비스입니다.");
        }
    }

    //리뷰 삭제
    @PostMapping("/main/detail/{concertId}/delete/review")
    public ResponseEntity<?> deleteReview(@CookieValue(value = "accessToken", required = false) String token,@PathVariable String concertId, @RequestBody Map<String,?> requestBody) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username=jwtUtil.getUsernameFromToken(token);
            SiteUser siteUser=userRepository.findByUsername(username);
            String author=requestBody.get("author").toString();
            Long id=Long.valueOf(requestBody.get("reviewId").toString());
            if(author.equals(siteUser.getUsername())||siteUser.getCode()==3){
                reviewService.delete(concertId,id);
                return ResponseEntity.ok().body("리뷰가 삭제되었습니다.");
            } else {
                return ResponseEntity.badRequest().body("잘못된 접근입니다.");
            }
        } else{
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
        }
    }

    //리뷰 수정
    @PostMapping("/main/detail/{concertId}/rewrtie/review")
    public ResponseEntity<?> rewriteReview(@CookieValue(value = "accessToken", required = false) String token,@PathVariable String concertId, @RequestBody Map<String,?> requestBody){
        try{
            if (token != null && jwtUtil.validateToken(token)) {
                String username=jwtUtil.getUsernameFromToken(token);
                SiteUser siteUser=userRepository.findByUsername(username);
                String author=requestBody.get("author").toString();
                String content= (String) requestBody.get("content");
                Long id=Long.valueOf(requestBody.get("reviewId").toString());
                Integer starCount= (Integer) requestBody.get("starCount");
                float parseStarCount=  starCount.floatValue();
                if(author.equals(siteUser.getUsername())){
                    reviewService.rewrite(concertId,id,content,parseStarCount);
                    return ResponseEntity.ok().body("리뷰가 수정되었습니다.");
                } else {
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("잘못된 접근입니다.");
                }
            } else{
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
            }
        } catch (Exception e){
            return ResponseEntity.status(HttpStatus.NO_CONTENT).body("오류가 발생했습니다(내부동작오류)");
        }
    }

}
