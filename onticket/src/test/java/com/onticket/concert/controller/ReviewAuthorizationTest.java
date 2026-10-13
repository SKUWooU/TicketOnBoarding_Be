package com.onticket.concert.controller;

import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.Review;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ReviewRepository;
import com.onticket.concert.service.ReviewService;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.jwt.JwtUtil;
import com.onticket.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ReviewAuthorizationTest {
    private ReviewRepository reviews;
    private ConcertDetailRepository details;
    private UserRepository users;
    private JwtUtil jwt;
    private MockMvc mvc;
    private Review review;
    private ConcertDetail detail;
    private SiteUser actor;

    @BeforeEach
    void setUp() {
        reviews = mock(ReviewRepository.class);
        details = mock(ConcertDetailRepository.class);
        users = mock(UserRepository.class);
        jwt = mock(JwtUtil.class);
        detail = new ConcertDetail();
        detail.setConcertId("concert-1");
        detail.setAverageRating(3);
        review = new Review();
        review.setId(7L);
        review.setAuthor("owner");
        review.setContent("original");
        review.setStarCount(3);
        review.setConcertDetail(detail);
        actor = new SiteUser();
        actor.setUsername("intruder");
        actor.setCode(1);
        when(jwt.validateToken("fixture-token")).thenReturn(true);
        when(jwt.getUsernameFromToken("fixture-token")).thenReturn("intruder");
        when(users.findByUsername("intruder")).thenReturn(actor);
        when(reviews.findById(7L)).thenReturn(Optional.of(review));
        when(reviews.findByConcertDetail(detail)).thenReturn(List.of(review));
        mvc = standaloneSetup(new ReviewController(new ReviewService(reviews, details, users), jwt)).build();
    }

    @Test
    void forgedAuthorCannotDeleteAnotherUsersReview() throws Exception {
        request("concert-1", "delete", true, body("intruder"))
                .andExpect(status().isForbidden());
        assertUnchanged();
    }

    @Test
    void forgedAuthorCannotRewriteAnotherUsersReview() throws Exception {
        request("concert-1", "rewrite", true, body("intruder"))
                .andExpect(status().isForbidden());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "rewrite"})
    void ownerCannotUseReviewFromAnotherConcert(String operation) throws Exception {
        authenticateOwner();
        request("concert-2", operation, true, body("owner"))
                .andExpect(status().isNotFound());
        assertUnchanged();
    }

    @Test
    void adminDeletionAlsoRequiresConcertMembership() throws Exception {
        actor.setCode(3);
        request("concert-2", "delete", true, body("intruder"))
                .andExpect(status().isNotFound());
        assertUnchanged();
    }

    @Test
    void ownerCanRewriteDespiteUntrustedAuthorField() throws Exception {
        authenticateOwner();
        request("concert-1", "rewrite", true, body("another-name"))
                .andExpect(status().isOk());
        assertThat(review.getContent()).isEqualTo("updated");
        assertThat(review.getAuthor()).isEqualTo("owner");
        assertThat(detail.getAverageRating()).isEqualTo(4);
        verify(reviews).save(review);
        verify(details, atLeastOnce()).save(detail);
    }

    @Test
    void ownerCanDeleteAndUpdateAverage() throws Exception {
        authenticateOwner();
        when(reviews.findByConcertDetail(detail)).thenReturn(List.of());
        request("concert-1", "delete", true, body("ignored"))
                .andExpect(status().isOk());
        verify(reviews).delete(review);
        assertThat(detail.getAverageRating()).isZero();
    }

    @Test
    void databaseAdminCanDeleteAnotherUsersReview() throws Exception {
        actor.setCode(3);
        request("concert-1", "delete", true, body("ignored"))
                .andExpect(status().isOk());
        verify(reviews).delete(review);
    }

    @Test
    void adminCannotRewriteAnotherUsersReview() throws Exception {
        actor.setCode(3);
        request("concert-1", "rewrite", true, body("intruder"))
                .andExpect(status().isForbidden());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "rewrite"})
    void absentReviewReturnsNotFound(String operation) throws Exception {
        when(reviews.findById(7L)).thenReturn(Optional.empty());
        request("concert-1", operation, true, body("intruder"))
                .andExpect(status().isNotFound());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "rewrite"})
    void missingTokenIsUnauthorized(String operation) throws Exception {
        request("concert-1", operation, false, body("intruder"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reviews, details, users, jwt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "rewrite"})
    void deletedAccountCannotModifyReviews(String operation) throws Exception {
        when(users.findByUsername("intruder")).thenReturn(null);
        request("concert-1", operation, true, body("intruder"))
                .andExpect(status().isForbidden());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "rewrite"})
    void malformedReviewIdIsBadRequest(String operation) throws Exception {
        request("concert-1", operation, true, "{\"reviewId\":null,\"content\":\"x\",\"starCount\":4}")
                .andExpect(status().isBadRequest());
        assertUnchanged();
    }

    private org.springframework.test.web.servlet.ResultActions request(
            String concert, String operation, boolean authenticated, String body) throws Exception {
        var request = post("/main/detail/" + concert + "/" + operation + "/review")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (authenticated) request.cookie(new Cookie("accessToken", "fixture-token"));
        return mvc.perform(request);
    }

    private String body(String author) {
        return "{\"reviewId\":7,\"author\":\"" + author
                + "\",\"content\":\"updated\",\"starCount\":4,\"code\":3}";
    }

    private void authenticateOwner() {
        actor.setUsername("owner");
        when(jwt.getUsernameFromToken("fixture-token")).thenReturn("owner");
        when(users.findByUsername("owner")).thenReturn(actor);
    }

    private void assertUnchanged() {
        assertThat(review.getContent()).isEqualTo("original");
        assertThat(review.getStarCount()).isEqualTo(3);
        assertThat(detail.getAverageRating()).isEqualTo(3);
        verify(reviews, never()).save(any());
        verify(reviews, never()).delete(any(Review.class));
        verify(reviews, never()).deleteById(any());
        verify(details, never()).save(any());
    }
}
