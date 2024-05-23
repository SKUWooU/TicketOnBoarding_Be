package com.onticket.concert.batch.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.onticket.concert.batch.dto.KopisDetailDto;
import com.onticket.concert.batch.dto.KopisDto;
import com.onticket.concert.batch.service.KopisService;
import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.repository.ConcertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;


//Batch 작업
@RequiredArgsConstructor
@Configuration
public class KopisBatchConfig {
    private final KopisService KopisService;
    private final ConcertRepository concertRepository;

    @Bean
    public Job kopisJob(JobRepository jobRepository, Step step) {
        return new JobBuilder("kopisJob"+System.currentTimeMillis(),jobRepository)
                .start(step)
                .build();
    }

    @Bean
    public Step step(JobRepository jobRepository, Tasklet tasklet,
                      PlatformTransactionManager platformTransactionManager) {
        return new StepBuilder("step", jobRepository)
                .tasklet(tasklet, platformTransactionManager).build();
    }

    @Bean
    public Tasklet tasklet(KopisService kopisService){
        return ((contribution, chunkContext) -> {

            List<KopisDto> kopisDtoList = kopisService.getConcertData();
            for (KopisDto kopisDto : kopisDtoList) {
                //------------------------------------ Concert Table 데이터
                Concert concert=kopisService.createConcert(kopisDto);


                //------------------------------------ ConcertDetail
                //해당DTO의 ID 값으로 API요청- 코드 중첩을 줄이기 위해 tasklet 에서 실행
                JsonNode jsonNode=kopisService.sendDetailRequests(kopisDto.getConcertId());
                JsonNode dtoNode=jsonNode.path("db");
                KopisDetailDto kopisDetailDto = kopisService.convertXmlToKopisDetailDto(dtoNode);


                String placeId=kopisService.getPlaceId(kopisDetailDto.getPlace());

                kopisService.createConcertDetailTable(kopisDetailDto);


                //------------------------------------ ConcertTime Table
                //ConcertTime Table에 넣을 공연시간 Data
                Map<String, List<String>> data= kopisService.parse(kopisDetailDto.getDateGuidance());
                //ConcertTime Table 데이터 생성
                kopisService.createConcertTimeTable(concert,data);



            }
            return RepeatStatus.FINISHED;
        });
    }
}