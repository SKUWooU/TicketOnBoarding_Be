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
import java.util.Optional;


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
                //####################################################################################//
                //-------------------------------Concert Table 생성  ----------------------------------//
                //####################################################################################//


                Optional<Concert> isConcert= concertRepository.findById(kopisDto.getConcertId());
                if(isConcert.isEmpty()){
                    //####################################################################################//
                    //-------------------------------Concert Table 생성  ----------------------------------//
                    //####################################################################################//

                    Concert concert=kopisService.createConcert(kopisDto);


                    //------------------------------------ 공통
                    //해당DTO의 ID 값으로 API요청- 코드 중첩을 줄이기 위해 tasklet 에서 실행
                    JsonNode jsonNode=kopisService.sendDetailRequests(kopisDto.getConcertId());
                    JsonNode dtoNode=jsonNode.path("db");
                    KopisDetailDto kopisDetailDto = kopisService.convertXmlToKopisDetailDto(dtoNode);



                    //####################################################################################//
                    //----------------------------------Place Table 생성-----------------------------------//
                    //####################################################################################//

                    //시설이름
                    String placeName=kopisDetailDto.getPlace();

                    //ConcertDetail 테이블에 placeID 추가 + Place 테이블에 추가 하기위해
                    List<String> placeIdAndSidoAndGugun=kopisService.getPlaceIdAndSidoAndGugun(placeName);

                    //시설ID
                    String placeId=placeIdAndSidoAndGugun.get(0);


                    //Place Table 생성
                    kopisService.createPlaceTable(placeName ,placeIdAndSidoAndGugun);

                    //Concert Detail 생성
                    kopisService.createConcertDetailTable(kopisDetailDto,placeId);


                    //------------------------------------ ------------------------------ConcertTime Table
                    //ConcertTime Table에 넣을 공연시간 Data
                    Map<String, List<String>> data= kopisService.parse(kopisDetailDto.getDateGuidance());
                    //ConcertTime Table 데이터 생성
                    List<ConcertTime> concertTimeList=kopisService.createConcertTimeTable(concert,data);
                    kopisService.createSeat(concertTimeList);
                }



            }
            return RepeatStatus.FINISHED;
        });
    }
}