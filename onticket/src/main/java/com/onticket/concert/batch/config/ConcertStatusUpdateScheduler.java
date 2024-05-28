package com.onticket.concert.batch.config;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;


//스케줄러
//@RequiredArgsConstructor
//@Configuration
//@EnableScheduling
//public class ConcertStatusUpdateScheduler {
//
//    private final JobLauncher jobLauncher;
//    private final Job updateConcertStatusJob;
//    private final Job kopisJob;
//
//
//
//    @Scheduled(cron = "0 0 0 * * ?") // 매일 자정에 실행
//    public void runUpdateConcertStatusJob() {
//        try {
//
//            //상태 업데이트
//            jobLauncher.run(updateConcertStatusJob, new JobParametersBuilder()
//                    .addLong("time", System.currentTimeMillis())
//                    .toJobParameters());
////            jobLauncher.run(kopisJob, new JobParametersBuilder()
////                    .addLong("time", System.currentTimeMillis())
////                    .toJobParameters());
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//}
