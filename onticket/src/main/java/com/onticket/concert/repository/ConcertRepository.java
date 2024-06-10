package com.onticket.concert.repository;
import com.onticket.concert.domain.Concert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.awt.print.Pageable;
import java.util.List;

public interface ConcertRepository extends JpaRepository<Concert,String> {

    //공연이름으로 검색
    @Query("SELECT c FROM Concert c JOIN c.concertDetail cd ON c.concertId = cd.concertId WHERE c.concertName LIKE %:concertName%")
    List<Concert> findByConcertNameContaining(@Param("concertName") String concertName);

    //공연상세
    @Query("SELECT c FROM Concert c JOIN c.concertDetail cd ON c.concertId = cd.concertId WHERE c.concertId = :concertId")
    Concert findByConcertId(@Param("concertId") String concertId);

    //관리자픽 공연
    List<Concert> findByOnTicketPickNot(int onticketpick);


    //장르별 공연
    @Query(value = "SELECT c.* FROM CONCERT c JOIN CONCERT_DETAIL cd ON c.CONCERT_ID = cd.CONCERT_ID WHERE c.GENRE = :genre", nativeQuery = true)
    List<Concert> findByGenre(@Param("genre") String genre);


    //지열별 공연
    @Query(value = "SELECT c.* FROM CONCERT c " +
            "JOIN CONCERT_DETAIL cd ON c.CONCERT_ID = cd.CONCERT_ID " +
            "JOIN PLACE p ON cd.PLACE_ID = p.PLACE_ID " +
            "WHERE p.SIDO = :sido", nativeQuery = true)
    List<Concert> findBySido(@Param("sido") String sido);


    @Query("SELECT c FROM Concert c JOIN c.concertDetail cd WHERE c.status = '공연중' or c.status = '공연예정' ORDER BY cd.averageRating DESC")
    List<Concert> findTop4ByOrderByAverageRatingDesc();
}

//@Query("SELECT cd FROM ConcertDetail cd ORDER BY cd.averageRating DESC")
//    List<ConcertDetail> findTop4ByOrderByAverageRatingDesc();