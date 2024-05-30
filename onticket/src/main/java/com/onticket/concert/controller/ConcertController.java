package com.onticket.concert.controller;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.Review;
import com.onticket.concert.dto.DetailDto;
import com.onticket.concert.dto.MainDto;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.service.ConcertService;

import com.onticket.concert.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;


@RequiredArgsConstructor
@RestController
public class ConcertController {
    private final ConcertService concertService;
    private final ConcertRepository concertRepository;
    private final ReviewService reviewService;

    @GetMapping("/main")
    public ResponseEntity<Map<String, List<MainDto>>> getMainPage() {

        List<MainDto> onTicketPickList= concertService.getMdPickConcert();
        List<MainDto> MostPopularConcertList=concertService.getMostPopularConcert();
        Map<String, List<MainDto>> map= new HashMap<>();
        map.put("onTicketPickList", onTicketPickList);
        map.put("MostPopularConcertList", MostPopularConcertList);
        return ResponseEntity.ok(map);

    }

    @GetMapping("/main/detail/{concert_id}")
    public ResponseEntity<DetailDto> getConcertDetail(@PathVariable("concert_id") String concertId) {
        DetailDto detailDto= concertService.getConcertDetail(concertId);

        return ResponseEntity.ok(detailDto);
    }

    //장르별 공연
    @GetMapping("/main/genre/{category}")
    public ResponseEntity<List<Concert>> getTheater(@PathVariable String category){
        String genre= concertService.convertStringToGenre(category);
        List<Concert> theatherList = concertService.getGenreConcert(genre);

        return ResponseEntity.ok(theatherList);
    }

    //지역별 공연
    @GetMapping("/main/region/{category}")
    public ResponseEntity<List<Concert>> getTheaterRegion(@PathVariable String category){
        String region = concertService.convertStringToRegion(category);
        List<Concert> regionList = concertService.getRegionConcert(region);
        return ResponseEntity.ok(regionList);
    }

    //검색
    @GetMapping("/main/search")
    public ResponseEntity<List<MainDto>> searchConcerts(@RequestParam(value = "concertname", required = false) String concertName) {
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
}
