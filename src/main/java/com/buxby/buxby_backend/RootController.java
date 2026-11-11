package com.buxby.buxby_backend;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 백엔드 주소로 바로 들어오면 API 문서(Swagger UI)를 보여준다
@Hidden
@Controller
public class RootController {

    @GetMapping("/")
    public String root() {
        return "redirect:/swagger-ui/index.html";
    }
}
