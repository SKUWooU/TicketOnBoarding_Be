package com.onticket.concert.batch.service;
import org.springframework.stereotype.Service;

import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import java.util.Arrays;
import java.util.List;

@Service
public class KopisService {

    private static final String BASE_URL = "http://kopis.or.kr/openApi/restful/pblprfr";
    private static final String API_KEY = "YOUR_API_KEY";  // 여기에 API 키를 입력하세요.

    public void sendRequests() {
        RestTemplate restTemplate = new RestTemplate();

        List<String> params = Arrays.asList("param1", "param2", "param3");  // 요청할 파라미터 목록

        for (String param : params) {
            String url = BASE_URL + "?service=" + API_KEY + "&param=" + param;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            // 응답 처리
            System.out.println(response.getBody());
        }
    }
}
