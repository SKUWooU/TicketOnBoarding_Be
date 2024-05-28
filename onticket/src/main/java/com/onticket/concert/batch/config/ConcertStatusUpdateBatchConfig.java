//package com.onticket.concert.batch.config;
//
//import com.onticket.concert.batch.service.KopisService;
//import com.onticket.concert.domain.Concert;
//import com.onticket.concert.repository.ConcertRepository;
//import lombok.RequiredArgsConstructor;
//import org.springframework.batch.core.Job;
//import org.springframework.batch.core.Step;
//import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
//import org.springframework.batch.core.job.builder.JobBuilder;
//import org.springframework.batch.core.repository.JobRepository;
//import org.springframework.batch.core.step.builder.StepBuilder;
//import org.springframework.batch.core.step.tasklet.Tasklet;
//import org.springframework.batch.repeat.RepeatStatus;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.transaction.PlatformTransactionManager;
//
//import java.time.LocalDate;
//import java.time.format.DateTimeFormatter;
//import java.util.List;
//
//
//
////날짜에 따라 공연상태 바꾸는 배치
//@Configuration
//@EnableBatchProcessing
//@RequiredArgsConstructor
//public class ConcertStatusUpdateBatchConfig {
//    private final ConcertRepository concertRepository;
//    private final KopisService kopisService;
//    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyMMdd");
//    @Bean
//    public Job updateConcertStatusJob(JobRepository jobRepository, Step updateConcertStatusStep) {
//        return new JobBuilder("updateConcertStatusJob" + System.currentTimeMillis(), jobRepository)
//                .start(updateConcertStatusStep)
//                .build();
//    }
//
//    @Bean
//    public Step updateConcertStatusStep(JobRepository jobRepository, PlatformTransactionManager platformTransactionManager) {
//        return new StepBuilder("updateConcertStatusStep", jobRepository)
//                .tasklet(updateConcertStatusTasklet(), platformTransactionManager)
//                .build();
//    }
//
//    @Bean
//    public Tasklet updateConcertStatusTasklet() {
//        return (contribution, chunkContext) -> {
//            List<Concert> concerts = concertRepository.findAll();
//            LocalDate today = LocalDate.now();
//            for (Concert concert : concerts) {
//                LocalDate startDate = concert.getStartDate();
//                LocalDate endDate = concert.getEndDate();
//
//                if (startDate != null && startDate.equals(today)) {
//                    concert.setStatus("공연중");
//                } else if (endDate != null && endDate.isBefore(today)) {
//                    concert.setStatus("공연종료");
//                }
//                concertRepository.save(concert);
//            }
//            return RepeatStatus.FINISHED;
//        };
//    }
//}
