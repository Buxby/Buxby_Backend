package com.buxby.buxby_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class BuxbyBackendApplication {

	public static void main(String[] args) {
		// 배포 서버(Render)는 UTC라서, 시간대 없이 쓰는 LocalDateTime.now()가 한국 시간이 되도록 고정한다
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
		SpringApplication.run(BuxbyBackendApplication.class, args);
	}

}
