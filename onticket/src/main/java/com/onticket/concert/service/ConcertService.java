package com.onticket.concert.service;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ConcertRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
public class ConcertService {

    private final ConcertRepository concertRepository;
    private final ConcertDetailRepository concertDetailRepository;

    public List<Concert> getMdPickConcert(){
        return concertRepository.findByOnTicketPickNot(0);
    }

    @Transactional(readOnly = true)
    public List<Concert> getMostPopularConcert(){
        List<Concert> concerts= concertRepository.findTop4ByOrderByAverageRatingDesc();

        List<Concert> top4= new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            top4.add(concerts.get(i));
        }
        return top4;
    }
    //url 한글로 바꾸기
    public String convertStringToGenre(String string){
        switch (string.toLowerCase()) {
            case "westmusic":
                return "서양음악(클래식)";
            case "koreanmusic":
                return "한국음악(국악)";
            // 필요한 경우 다른 카테고리도 추가
            case "musical":
                return "뮤지컬";
            case "play":
                return "연극";
            case "contemporarymusic":
                return "대중음악";
            case "dance":
                return "무용";
            case "circus":
                return "서커스/마술";
            case "complex":
                return "복합";
            default:
                throw new IllegalArgumentException("잘못된url 정보");
        }
    }

    //장르별 공연 뽑기
    public List<Concert> getGenreConcert(String genre){
        return concertRepository.findByGenre(genre);
    }

    //url 한글로 바꾸기
    public String convertStringToRegion(String string){
        switch (string.toLowerCase()) {
            case "seoul":
                return "서울";
            case "busan":
                return "부산";
            case "daejun":
                return "대전";
            case "gwangju":
                return "광주";
            case "daegu":
                return "대구";
            case "ulsan":
                return "울산";
            case "incheon":
                return "인천";
            case "kangwon":
                return "강원";
            case "gyeonggi":
                return "경기";
            case "chungbuk":
                return "충북";
            case "chungnam":
                return "충남";
            case "jeonnam":
                return "전남";
            case "jeonbuk":
                return "전북";
            case "gyeonnam":
                return "경남";
            case "gyeonbuk":
                return "경북";
            default:
                throw new IllegalArgumentException("잘못된url 정보");
        }
    }

    //지역별 공연
    public List<Concert> getRegionConcert(String region){
        return concertRepository.findBySido(region);
    }
}
