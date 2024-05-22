package com.onticket.concert.repository;
import com.onticket.concert.domain.Concert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertRepository extends JpaRepository<Concert,String> {
    boolean findByConcertId(String concertId);
}
