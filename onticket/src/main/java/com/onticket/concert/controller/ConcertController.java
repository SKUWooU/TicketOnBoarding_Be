package com.onticket.concert.controller;


import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.dto.MainDto;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.service.ConcertService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.*;



@RequiredArgsConstructor
@RestController
public class ConcertController {
    private final ConcertService concertService;
    private final ConcertRepository concertRepository;

    @GetMapping("/main")
    public ResponseEntity<Map<String, List<MainDto>>> getMainPage() {

        List<MainDto> onTicketPickList= concertService.getMdPickConcert();
        List<MainDto> MostPopularConcertList=concertService.getMostPopularConcert();
        Map<String, List<MainDto>> map= new HashMap<>();
        map.put("onTicketPickList", onTicketPickList);
        map.put("MostPopularConcertList", MostPopularConcertList);
        return ResponseEntity.ok(map);

    }

//    @GetMapping("/detail/{concert_id}")
//    public ResponseEntity<ConcertDetail> getConcertDetail(@PathVariable("concert_id") String concertId) {
//        ConcertDetail concertDetail= concertService.getConcertDetail(concertId);
//        return ResponseEntity.ok(concertDetail);
//    }

    //장르별 공연
    @GetMapping("/genre/{category}")
    public ResponseEntity<List<Concert>> getTheater(@PathVariable String category){
        String genre= concertService.convertStringToGenre(category);
        List<Concert> theatherList = concertService.getGenreConcert(genre);

        return ResponseEntity.ok(theatherList);
    }

    //지역별 공연
    @GetMapping("/region/{category}")
    public ResponseEntity<List<Concert>> getTheaterRegion(@PathVariable String category){
        String region = "서울";
        List<Concert> regionList = concertService.getRegionConcert(region);
        return ResponseEntity.ok(regionList);
    }

}
