package com.onticket.concert.repository;

import com.onticket.concert.domain.ConcertDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ConcertDetailRepository extends JpaRepository<ConcertDetail, String> {
    ConcertDetail findByConcertId(String concertId);
}
