package com.onticket.concert.service;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.Place;
import com.onticket.concert.dto.DetailDto;
import com.onticket.concert.dto.MainDto;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ConcertRepository;

import com.onticket.concert.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
public class ConcertService {

    private final ConcertRepository concertRepository;
    private final ConcertDetailRepository concertDetailRepository;
    private final PlaceRepository placeRepository;


    //운연자 픽 데이터
    public List<MainDto> getMdPickConcert(){
        List<Concert> concertList = concertRepository.findByOnTicketPickNot(0);

        List<MainDto> mainDtoList = getMainDtoList(concertList);
//        DateTimeFormatter formatter= DateTimeFormatter.ofPattern("yyyy-MM-dd");
//        for(Concert concert : concertList){
//            MainDto mainDto = new MainDto();
//            mainDto.setConcertID(concert.getConcertId());
//            mainDto.setConcertName(concert.getConcertName());
//            mainDto.setStartDate(concert.getStartDate().format(formatter));
//            mainDto.setEndDate(concert.getEndDate().format(formatter));
//            mainDto.setPrice(concert.getConcertDetail().getPrice());
//            Place place = placeRepository.findByPlaceId(concert.getConcertDetail().getPlaceId());
//            mainDto.setSido(place.getSido());
//            mainDto.setGugun(place.getGugun());
//            mainDto.setPlaceName(place.getPlaceName());
//            mainDto.setPosterUrl(concert.getPosterUrl());
//            mainDto.setAverageRating(concert.getConcertDetail().getAverageRating());
//            mainDtoList.add(mainDto);
//        }
        return mainDtoList;
    }

    //평점순
    public List<MainDto> getMostPopularConcert(){
        List<Concert> concerts= concertRepository.findTop4ByOrderByAverageRatingDesc();

        List<Concert> top4= new ArrayList<>();
//        DateTimeFormatter formatter= DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = 0; i < 4; i++) {
            top4.add(concerts.get(i));
        }
        List<MainDto> mainDtoList=getMainDtoList(top4);
//        for(Concert tops:top4){
//            MainDto mainDto = new MainDto();
//            mainDto.setConcertID(tops.getConcertId());
//            mainDto.setConcertName(tops.getConcertName());
//            mainDto.setStartDate(tops.getStartDate().format(formatter));
//            mainDto.setEndDate(tops.getEndDate().format(formatter));
//            mainDto.setPrice(tops.getConcertDetail().getPrice());
//            Place place = placeRepository.findByPlaceId(tops.getConcertDetail().getPlaceId());
//            mainDto.setSido(place.getSido());
//            mainDto.setGugun(place.getGugun());
//            mainDto.setPlaceName(place.getPlaceName());
//            mainDto.setPosterUrl(tops.getPosterUrl());
//            mainDto.setAverageRating(tops.getConcertDetail().getAverageRating());
//            mainDtoList.add(mainDto);
//        }
        return mainDtoList;
    }


    //장르url 한글로 바꾸기
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
            case "daejeon":
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
            case "gyeongnam":
                return "경남";
            case "gyeongbuk":
                return "경북";
            case "jeju":
                return "제주";

            default:
                throw new IllegalArgumentException("잘못된url 정보");
        }
    }

    //지역별 공연
    public List<Concert> getRegionConcert(String region){
        return concertRepository.findBySido(region);
    }

    //공연상세정보+리뷰 반환
    public DetailDto getConcertDetail(String concertId) {
        Concert concert = concertRepository.findByConcertId(concertId);
        DetailDto detailDto = new DetailDto();
        detailDto.setConcertId(concertId);
        detailDto.setConcertName(concert.getConcertName());
        detailDto.setPerformers(concert.getConcertDetail().getPerformers());
        detailDto.setStartTime(concert.getConcertDetail().getStartTime());
        detailDto.setAge(concert.getConcertDetail().getAge());
        detailDto.setPrice(concert.getConcertDetail().getPrice());
        detailDto.setPerformers(concert.getConcertDetail().getPerformers());
        detailDto.setCrew(concert.getConcertDetail().getCrew());
        detailDto.setCompany(concert.getConcertDetail().getCompany());
        detailDto.setGenre(concert.getGenre());
        detailDto.setPosterUrl(concert.getPosterUrl());
        Place place = placeRepository.findByPlaceId(concert.getConcertDetail().getPlaceId());
        detailDto.setPlaceName(place.getPlaceName());
        detailDto.setAddr(place.getAddr());
        detailDto.setLa(place.getLatitude());
        detailDto.setLo(place.getLongitude());
        detailDto.setReviewList(concert.getConcertDetail().getReviews());
        return detailDto;
    }

    //콘서트이름으로 검색
    public List<MainDto> searchConcertsByName(String concertName) {
        List<Concert> concertList=concertRepository.findByConcertNameContaining(concertName);
        DateTimeFormatter formatter= DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<MainDto> mainDtoList=new ArrayList<>();
        for(Concert concert:concertList){
            MainDto mainDto = new MainDto();
            mainDto.setConcertID(concert.getConcertId());
            mainDto.setConcertName(concert.getConcertName());
            mainDto.setStartDate(concert.getStartDate().format(formatter));
            mainDto.setEndDate(concert.getEndDate().format(formatter));
            mainDto.setPrice(concert.getConcertDetail().getPrice());
            Place place = placeRepository.findByPlaceId(concert.getConcertDetail().getPlaceId());
            mainDto.setSido(place.getSido());
            mainDto.setGugun(place.getGugun());
            mainDto.setPlaceName(place.getPlaceName());
            mainDto.setPosterUrl(concert.getPosterUrl());
            mainDto.setAverageRating(concert.getConcertDetail().getAverageRating());
            mainDtoList.add(mainDto);
        }
        return mainDtoList;
    }

    public List<MainDto> getMainDtoList(List<Concert> concertList) {
        DateTimeFormatter formatter= DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<MainDto> mainDtoList=new ArrayList<>();
        for(Concert concert:concertList){
            MainDto mainDto = new MainDto();
            mainDto.setConcertID(concert.getConcertId());
            mainDto.setConcertName(concert.getConcertName());
            mainDto.setStartDate(concert.getStartDate().format(formatter));
            mainDto.setEndDate(concert.getEndDate().format(formatter));
            mainDto.setPrice(concert.getConcertDetail().getPrice());
            Place place = placeRepository.findByPlaceId(concert.getConcertDetail().getPlaceId());
            mainDto.setSido(place.getSido());
            mainDto.setGugun(place.getGugun());
            mainDto.setPlaceName(place.getPlaceName());
            mainDto.setPosterUrl(concert.getPosterUrl());
            mainDto.setAverageRating(concert.getConcertDetail().getAverageRating());
            mainDtoList.add(mainDto);
        }
        return mainDtoList;
    }
}
