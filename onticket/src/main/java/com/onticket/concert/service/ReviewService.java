package com.onticket.concert.service;

import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.Review;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ReviewRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ConcertDetailRepository concertDetailRepository;

    @Transactional
    public Review addReview(String concertDetailId, String author, String content, float starCount) {
        ConcertDetail concertDetail = concertDetailRepository.findById(concertDetailId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid concertDetail ID"));

        Review review = new Review();
        review.setAuthor(author);
        review.setDate(LocalDateTime.now());
        review.setContent(content);
        review.setConcertDetail(concertDetail);
        review.setStarCount(starCount);
        updateAverageRating(concertDetail);
        return reviewRepository.save(review);
    }

    //리뷰평균 내기
    private void updateAverageRating(ConcertDetail concertDetail) {
        List<Review> reviews = concertDetail.getReviews();
        float totalRating = 0;
        for (Review review : reviews) {
            totalRating += review.getStarCount();
        }
        float averageRating = totalRating / reviews.size();
        concertDetail.setAverageRating(averageRating);

        concertDetailRepository.save(concertDetail);
    }

    public List<Review> getReviews(String concertDetailId) {
        ConcertDetail concertDetail = concertDetailRepository.findById(concertDetailId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid concertDetail ID"));

        return concertDetail.getReviews();
    }
}
