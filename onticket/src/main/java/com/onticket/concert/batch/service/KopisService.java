package com.onticket.concert.batch.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onticket.concert.batch.config.KopisApi;
import com.onticket.concert.batch.dto.KopisDetailDto;
import com.onticket.concert.batch.dto.KopisDto;
import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;

@RequiredArgsConstructor
@Service
public class KopisService {

    private final ConcertTimeRepository concertTimeRepository;
    private final KopisApi kopisApi;
    private final ConcertRepository concertRepository;
    WebClient webClient;
///////////////////////////////-----------Concert   테이블-------------//////////////////////////////////


    //Tasklet에서 호출할 함수:로직순서===> api 요청 -> Xml데이터를 json으로 변환 -> Json을 KopisDto 객체로 매핑 -> KopisDto들을 DB에 저장
    public List<KopisDto> getConcertData(){
        //json 형태로 가져오기
        JsonNode jsonNode=sendRequests();
        //api 형식에 맞춰 "db" 값 뽑기
        JsonNode dtoNode=jsonNode.path("db");
        //반환할 데이터 객체 초기화
        List<KopisDto> kopisDtoList = new ArrayList<>();
        if (dtoNode.isArray()) {
            for (JsonNode node : dtoNode) {
                //json을 KopisDto 객체로 변환
                KopisDto kopisDto = convertXmlToKopisDto(node);
                kopisDtoList.add(kopisDto);
            }
            return kopisDtoList;
        } else {
            KopisDto kopisDto =  convertXmlToKopisDto(dtoNode);
        }
        return Collections.emptyList();
    }

    //Uri(uniform resource identifier) 요청하고 parseXml 함수로 보내서 json 트리구조로 변환
    public JsonNode sendRequests() {

        //uri를 생성하기 위해 사용
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory();
        //특수문자 그대로 사용
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);

        // WebClient-resttemplate 대체로 사용
        webClient = WebClient.builder().uriBuilderFactory(factory).build();

        //api 요청 uri
        String responseBody = webClient.get()
                .uri(builder -> builder
                        .scheme("http")
                        .host("www.kopis.or.kr")
                        .path("/openApi/restful/pblprfr")
                        .queryParam("service", kopisApi.getKopisapikey())
                        .queryParam("stdate","20240510" )
                        .queryParam("eddate", "20240530")
                        .queryParam("cpage", 1)
                        .queryParam("rows", 30)
                        .build())
                .retrieve() //요청을 보내고 응답을 Retrieve
                .bodyToMono(String.class)
                .block(); // 동기적으로 결과를 얻음
        return parseXml(responseBody);
    }

    //xml 데이터를 json 트리구조로 변환하는 함수
    public JsonNode parseXml(String responseBody) {
        try{
            XmlMapper xmlMapper = new XmlMapper();
            return xmlMapper.readTree(responseBody);
        } catch (Exception e){
            e.printStackTrace();
            return null;
        }
    }

    //KopisDto 와 매핑
    public KopisDto convertXmlToKopisDto(JsonNode jsonNode){
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.treeToValue(jsonNode,KopisDto.class);
        }catch (Exception e){
            e.printStackTrace();
            return null;
        }
    }

    //DB에 저장
    @Transactional
    public Concert createConcert(KopisDto kopisDto) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd");

        String concertId = kopisDto.getConcertId();
        String concertName = kopisDto.getConcertName();
        String place = kopisDto.getPlace();
        String posterUrl = kopisDto.getPosterUrl();
        String genre = kopisDto.getGenre();
        String status = kopisDto.getStatus();
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(kopisDto.getStartDate(), formatter);
            endDate = LocalDate.parse(kopisDto.getEndDate(), formatter);
        } catch (DateTimeParseException e) {
            throw new RuntimeException(e);
        }

        Concert concert = new Concert();
        concert.setConcertId(concertId);
        concert.setConcertName(concertName);
        concert.setStartDate(startDate);
        concert.setEndDate(endDate);
        concert.setPosterurl(posterUrl);
        concert.setPlace(place);
        concert.setGenre(genre);
        concert.setStatus(status);
        concert.setSeatAmount(16);


        try {
            concertRepository.save(concert);
        } catch (DataIntegrityViolationException e) {
            // 사용자가 이미 존재하는 경우에 대한 처리
            throw new IllegalArgumentException("Concert already exists.");
        }


        return concert;
    }



/////////////////////////////-----------ConcertDetail   테이블-------------////////////////////////////////


    //ConcertDetail 테이블 생성
    public void createConcertDetailTable(KopisDetailDto kopisDetailDto){

    }

    //공연ID로 공연상세 API 요청
    public JsonNode sendDetailRequests(String concertid){
        //uri를 생성하기 위해 사용
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory();
        //특수문자 그대로 사용
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);

        // WebClient-resttemplate 대체로 사용
        webClient = WebClient.builder().uriBuilderFactory(factory).build();

        //api 요청 url
        String responseBody = webClient.get()
                .uri(builder -> builder
                        .scheme("http")
                        .host("www.kopis.or.kr")
                        .path("/openApi/restful/pblprfr/"+concertid)
                        .queryParam("service", kopisApi.getKopisapikey())
                        .build())
                .retrieve() //요청을 보내고 응답을 Retrieve
                .bodyToMono(String.class)
                .block(); // 동기적으로 결과를 얻음
        return parseXml(responseBody);
    }

    //KopisDetailDto 와 매핑
    public KopisDetailDto convertXmlToKopisDetailDto(JsonNode jsonNode) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.treeToValue(jsonNode,KopisDetailDto.class);
        }catch (Exception e){
            e.printStackTrace();
            return null;
        }
    }



//////////////////////////////-----------ConcertTime   테이블-------------////////////////////////////////



    //ConcertTime 테이블에 날짜별 테이블 만듬  -> dto 객체로 리팩토링 필요
    @Transactional
    public void createConcertTimeTable(Concert concert,Map<String, List<String>> data){
        concertRepository.save(concert);
        LocalDate startDate=concert.getStartDate();
        LocalDate endDate=concert.getEndDate();

        //일별로 나눠서 저장하는 로직
        List<ConcertTime> concertTimeList=new ArrayList<>();

        //공연 시작일 부터 종료일까지 돌면서 날짜+요일 데이터 넣기
        for(LocalDate date=startDate;!date.isAfter(endDate);date=date.plusDays(1)){
            DayOfWeek dayOfWeek= date.getDayOfWeek();
            String day=getKoreanDayOfWeek(dayOfWeek);

            //Setter
            if(data.containsKey(day)){
                for(String time:data.get(day)){
                    ConcertTime concertTime=new ConcertTime();
                    concertTime.setDate(date);
                    concertTime.setDayOfWeek(day+"요일");
                    concertTime.setStartTime(LocalTime.parse(time, DateTimeFormatter.ofPattern("H:mm")));
                    concertTime.setSeatAmount(16);
                    concertTime.setConcert(concert);

                    concertTimeList.add(concertTime);
                }
            }
        }
        concertTimeRepository.saveAll(concertTimeList);
    }

    //요일 String으로 변환
    public String getKoreanDayOfWeek(DayOfWeek dayOfWeek) {
        switch (dayOfWeek) {
            case MONDAY: return "월";
            case TUESDAY: return "화";
            case WEDNESDAY: return "수";
            case THURSDAY: return "목";
            case FRIDAY: return "금";
            case SATURDAY: return "토";
            case SUNDAY: return "일";
            default: throw new IllegalArgumentException("Invalid DayOfWeek: " + dayOfWeek);
        }
    }

    //공연시간데이터 파싱
    public Map<String, List<String>> parse(String dtguidance) {
        Pattern pattern = Pattern.compile("((?:월|화|수|목|금|토|일)요일(?: ~ (?:월|화|수|목|금|토|일)요일)?)\\((.*?)\\)");
        Map<String, List<String>> scheduleMap = new HashMap<>();

        Matcher matcher = pattern.matcher(dtguidance);
        while (matcher.find()) {
            //요일
            String dayRange = matcher.group(1);
            //시간
            String timeString = matcher.group(2);
            String[] times = timeString.split(",");
            List<String> days = parseDays(dayRange);

            for (String dayOfWeek : days) {
                scheduleMap.putIfAbsent(dayOfWeek, new ArrayList<>());
                for (String time : times) {
                    scheduleMap.get(dayOfWeek).add(time.trim());
                }
            }
        }

        return scheduleMap;
    }

    public List<String> parseDays(String dayRange) {
        List<String> days = new ArrayList<>();
        Map<String, Integer> dayOrder = Map.of(
                "월", 1, "화", 2, "수", 3, "목", 4, "금", 5, "토", 6, "일", 7
        );
        Map<Integer, String> reverseDayOrder = Map.of(
                1, "월", 2, "화", 3, "수", 4, "목", 5, "금", 6, "토", 7, "일"
        );

        if (dayRange.contains(" ~ ")) { // 요일 범위를 인식하고 처리하는 부분 추가
            String[] parts = dayRange.split(" ~ ");
            String startDay = parts[0].replace("요일", "").trim();
            String endDay = parts[1].replace("요일", "").trim();

            int startIndex = dayOrder.get(startDay);
            int endIndex = dayOrder.get(endDay);

            for (int i = startIndex; i <= endIndex; i++) {
                days.add(reverseDayOrder.get(i));
            }
        } else {
            days.add(dayRange.replace("요일", "").trim());
        }
        return days;
    }

    //현재시간-yyyymmdd 형식
    public String getNowDate() {
        LocalDate currentDate = LocalDate.now();
        return currentDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    //한달치 조회예정 yyyymmdd 형식
    public String getAfter30Date(){
        LocalDate currentDate = LocalDate.now();
        LocalDate dateAfter30Days = currentDate.plusDays(30);
        return dateAfter30Days.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }


}