package com.onticket.concert.controller;

import com.onticket.concert.domain.Review;
import com.onticket.concert.service.ReviewService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.onticket.user.jwt.JwtUtil;
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
    public ResponseEntity<?> deleteReview(@CookieValue(value = "accessToken", required = false) String token,@PathVariable String concertId, @Valid @RequestBody DeleteReviewRequest requestBody) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username=jwtUtil.getUsernameFromToken(token);
            reviewService.delete(concertId, requestBody.reviewId(), username);
            return ResponseEntity.ok().body("리뷰가 삭제되었습니다.");
        } else{
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
        }
    }

    //리뷰 수정
    @PostMapping("/main/detail/{concertId}/rewrite/review")
    public ResponseEntity<?> rewriteReview(@CookieValue(value = "accessToken", required = false) String token,@PathVariable String concertId, @Valid @RequestBody RewriteReviewRequest requestBody){
        if (token != null && jwtUtil.validateToken(token)) {
            String username=jwtUtil.getUsernameFromToken(token);
            reviewService.rewrite(concertId, requestBody.reviewId(), username,
                    requestBody.content(), requestBody.starCount());
            return ResponseEntity.ok().body("리뷰가 수정되었습니다.");
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeleteReviewRequest(@NotNull @Positive Long reviewId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RewriteReviewRequest(
            @NotNull @Positive Long reviewId, @NotNull String content, @NotNull Float starCount) {}
}
