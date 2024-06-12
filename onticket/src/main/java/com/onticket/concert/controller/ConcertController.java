package com.onticket.concert.controller;

import com.onticket.concert.domain.*;
import com.onticket.concert.dto.*;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.SeatRepository;
import com.onticket.concert.service.ConcertService;

import com.onticket.concert.service.ReviewService;
import com.onticket.concert.service.SeatReservationService;
import com.onticket.user.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.time.LocalDateTime;
import java.util.*;


@RequiredArgsConstructor
@RestController
public class ConcertController {
    private final ConcertService concertService;
    private final SeatReservationService seatReservationService;
    private final ConcertRepository concertRepository;
    private final ReviewService reviewService;
    private final JwtUtil jwtUtil;
    //메인페이지
    @GetMapping("/main")
    public ResponseEntity<Map<String, List<MainDto>>> getMainPage() {

        List<MainDto> onTicketPickList= concertService.getMdPickConcert();
        List<MainDto> MostPopularConcertList=concertService.getMostPopularConcert();
        Map<String, List<MainDto>> map= new HashMap<>();
        map.put("onTicketPickList", onTicketPickList);
        map.put("MostPopularConcertList", MostPopularConcertList);
        return ResponseEntity.ok(map);

    }

    //상세페이지
    @GetMapping("/main/detail/{concertId}")
    public ResponseEntity<DetailDto> getConcertDetail(@PathVariable("concertId") String concertId) {
        DetailDto detailDto= concertService.getConcertDetail(concertId);

        return ResponseEntity.ok(detailDto);
    }

    //달력데이터-해당 날짜만 띄울 수 있도록
    @GetMapping("/main/detail/{concertId}/calendar")
    public ResponseEntity<List<CalDto>> getCalendar(@PathVariable("concertId") String concertId) {
        List<CalDto> calDtoList=seatReservationService.getAllOfConcertTime(concertId);
        return ResponseEntity.ok(calDtoList);
    }

    //해당 공연 시간에 대한 좌석 데이터 출력
    @GetMapping("/main/detail/{concertId}/calendar/{timeId}")
    public ResponseEntity<List<SeatDto>> getSeat(@PathVariable("concertId") String concertId, @PathVariable("timeId")Long timeId) {
        return ResponseEntity.ok(seatReservationService.getSeatsByConcertTimeId(timeId));
    }

    //장르별 공연페이지
    @GetMapping("/main/genre/{category}")
    public ResponseEntity<List<MainDto>> getGenre(@PathVariable("category") String category){
        String genre= concertService.convertStringToGenre(category);
        List<Concert> genreConcertList = concertService.getGenreConcert(genre);
        List<MainDto> mainDtoList =concertService.getMainDtoList(genreConcertList);
        return ResponseEntity.ok(mainDtoList);
    }

    //지역별 공연페이지
    @GetMapping("/main/region/{category}")
    public ResponseEntity<List<MainDto>> getRegion(@PathVariable("category") String category){
        String region = concertService.convertStringToRegion(category);
        List<Concert> regionList = concertService.getRegionConcert(region);
        List<MainDto> mainDtoList =concertService.getMainDtoList(regionList);
        return ResponseEntity.ok(mainDtoList);
    }

    //검색페이지
    @GetMapping("/main/search")
    public ResponseEntity<List<MainDto>> search(@RequestParam(value = "concertname", required = false) String concertName) {
        if (concertName == null || concertName.trim().isEmpty()) {
            // 파라미터가 없는 경우 빈 리스트를 반환
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            // 인코딩된 문자열을 디코딩
            concertName = URLDecoder.decode(concertName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
        List<MainDto> dtoList = concertService.searchConcertsByName(concertName);
        return ResponseEntity.ok(dtoList);
    }

    //리뷰페이지
    @GetMapping("/main/detail/{concertId}/reviews")
    public ResponseEntity<List<Review>> getReviews(@PathVariable String concertId) {
        List<Review> reviewList = reviewService.getReviews(concertId);
        return ResponseEntity.ok(reviewList);
    }

    //리뷰포스트
    @PostMapping("/main/detail/{concertId}/register/review")
    public ResponseEntity<?> registerReview(@CookieValue(value = "accessToken", required = false) String token,@PathVariable("concertId") String concertId,@RequestBody Map<String,?> requestBody) {
        if (token != null && jwtUtil.validateToken(token)) {
            System.out.println(concertId);
            String username=jwtUtil.getUsernameFromToken(token);
            String content= (String) requestBody.get("content");
            Integer starCount= (Integer) requestBody.get("starCount");
            float parseStarCount=  starCount.floatValue();
            reviewService.addReview(concertId,username,content,parseStarCount);
            return ResponseEntity.ok().body("리뷰가 등록되었습니다.");
        } else {
            return ResponseEntity.badRequest().body("로그인이 필요한 서비스입니다.");
        }
    }

    //공연예약
    @PostMapping("/main/detail/{concertId}/reservation")
    public ResponseEntity<?> setReservation(@CookieValue(value = "accessToken", required = false) String token,@PathVariable("concertId") String concertId, @RequestBody ReservRequest reservRequest) throws Exception {
        if (token != null && jwtUtil.validateToken(token)) {
            String username=jwtUtil.getUsernameFromToken(token);
            seatReservationService.reserveSeat(username,concertId,reservRequest);
            LocalDateTime now = LocalDateTime.now();
            return ResponseEntity.ok().body(now);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
        }

    }
}
