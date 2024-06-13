package com.onticket.concert.service;

import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.Review;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ReviewRepository;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    public void delete(String concertDetailId,Long reviewId){
        ConcertDetail concertDetail=concertDetailRepository.findByConcertId(concertDetailId);
        reviewRepository.deleteById(reviewId);
        updateAverageRating(concertDetail);
        concertDetailRepository.save(concertDetail);
    }

    public void rewrite(String concertDetailId, Long reviewId, String content, float starCount) {
        Optional<Review> _review=reviewRepository.findById(reviewId);
        ConcertDetail concertDetail=concertDetailRepository.findByConcertId(concertDetailId);
        if (_review.isPresent()) {
            Review review=_review.get();
            review.setStarCount(starCount);
            review.setContent(content);
            reviewRepository.save(review);
            updateAverageRating(concertDetail);
            concertDetailRepository.save(concertDetail);
        }
    }
}
