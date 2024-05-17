package com.onticket;



import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

//카카오지도 API테스트
@Controller
public class HomeController {
    @GetMapping("/apitest")
    public String apitest() {
        return "home";
    }

}
