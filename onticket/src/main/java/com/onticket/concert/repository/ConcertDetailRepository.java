package com.onticket.concert.repository;

import com.onticket.concert.domain.ConcertDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertDetailRepository extends JpaRepository<ConcertDetail, String> {
}
