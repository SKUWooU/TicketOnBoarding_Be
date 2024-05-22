package com.onticket.concert.repository;
import com.onticket.concert.domain.ConcertTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertTimeRepository extends JpaRepository<ConcertTime,Long> {

}
