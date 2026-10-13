package com.onticket.concert.service;

import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.Review;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ReviewRepository;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ConcertDetailRepository concertDetailRepository;
    private final UserRepository userRepository;

    //리뷰생성+평균계산
    @Transactional
    public void create(String concertDetailId, String author, String content, float starCount) {
        ConcertDetail concertDetail = concertDetailRepository.findById(concertDetailId)
                .orElseThrow(() -> new IllegalArgumentException("concertId가 잘못되었습니다."));
        SiteUser siteUser =userRepository.findByUsername(author);
        String nickname=siteUser.getNickname();
        System.out.println(starCount);
        Review review = new Review();
        review.setAuthor(author);
        review.setDate(LocalDateTime.now());
        review.setContent(content);
        review.setConcertDetail(concertDetail);
        review.setNickName(nickname);
        review.setStarCount(starCount);
        reviewRepository.save(review);


        updateAverageRating(concertDetail);
        concertDetailRepository.save(concertDetail);

    }

    // 리뷰 평균을 계산하는 메서드
    private void updateAverageRating(ConcertDetail concertDetail) {
        List<Review> reviews = reviewRepository.findByConcertDetail(concertDetail);
        if (reviews.isEmpty()) {
            concertDetail.setAverageRating(0.0f);
        } else {
            float totalRating = 0;
            for (Review review : reviews) {
                totalRating += review.getStarCount();
            }
            float averageRating = totalRating / reviews.size();

            // NaN 값이 아닌 유효한 값으로 설정
            if (Float.isNaN(averageRating)) {
                averageRating = 0.0f;
            }

            concertDetail.setAverageRating(averageRating);
        }
        concertDetailRepository.save(concertDetail);
    }

    //리뷰출력
    public List<Review> getReviews(String concertDetailId) {
        ConcertDetail concertDetail = concertDetailRepository.findById(concertDetailId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid concertDetail ID"));

        return concertDetail.getReviews();
    }

    //리뷰삭제+평균계산
    @Transactional
    public void delete(String concertDetailId, Long reviewId, String username){
        Review review = requireAuthorizedReview(concertDetailId, reviewId, username, true);
        ConcertDetail concertDetail = review.getConcertDetail();
        reviewRepository.delete(review);
        updateAverageRating(concertDetail);
        concertDetailRepository.save(concertDetail);
    }

    @Transactional
    public void rewrite(String concertDetailId, Long reviewId, String username, String content, float starCount) {
        Review review = requireAuthorizedReview(concertDetailId, reviewId, username, false);
        ConcertDetail concertDetail = review.getConcertDetail();
        review.setStarCount(starCount);
        review.setContent(content);
        reviewRepository.save(review);
        updateAverageRating(concertDetail);
        concertDetailRepository.save(concertDetail);
    }

    private Review requireAuthorizedReview(
            String concertDetailId, Long reviewId, String username, boolean adminDeletionAllowed) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."));
        if (review.getConcertDetail() == null
                || !Objects.equals(review.getConcertDetail().getConcertId(), concertDetailId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공연에 속한 리뷰를 찾을 수 없습니다.");
        }
        SiteUser actor = userRepository.findByUsername(username);
        if (actor == null || (!Objects.equals(review.getAuthor(), username)
                && !(adminDeletionAllowed && actor.getCode() == 3))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "리뷰 변경 권한이 없습니다.");
        }
        return review;
    }
}
