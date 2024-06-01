package com.onticket.concert.service;

import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Reservation;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.CalDto;
import com.onticket.concert.dto.SeatDto;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
@RequiredArgsConstructor
@Service
public class SeatReservationService {

    private final SeatRepository seatRepository;
    private final ConcertTimeRepository concertTimeRepository;
    private final ReservationRepository ReservationRepository;
    private final ReservationRepository reservationRepository;

    //달력에서 사용할 데이터
    public List<CalDto> getAllOfConcertTime(String concertId){
        List<ConcertTime> concertTimeList=concertTimeRepository.findByConcert_ConcertId(concertId);
        List<CalDto> calDtoList=new ArrayList<>();
        for(ConcertTime concertTime:concertTimeList){
            CalDto calDto=new CalDto();
            calDto.setId(concertTime.getId());
            calDto.setDate(concertTime.getDate());
            calDto.setStartTime(concertTime.getStartTime());
            calDto.setSeatAmount(concertTime.getSeatAmount());
            calDto.setDayOfWeek(concertTime.getDayOfWeek());
            calDtoList.add(calDto);
        }
        return calDtoList;
    }

    //해당 타임의 좌석 정보
    public List<SeatDto> getSeatsByConcertTimeId(Long concertTimeId) {
        List<Seat> seatList= seatRepository.findByConcertTimeId(concertTimeId);
        List<SeatDto> seatDtoList=new ArrayList<>();
        for(Seat seat:seatList){
            SeatDto seatDto=new SeatDto();
            seatDto.setId(seat.getId());
            seatDto.setSeatNumber(seat.getSeatNumber());
            seatDto.setReserved(seat.isReserved());
            seatDtoList.add(seatDto);
        }
        return seatDtoList;
    }


    //좌석에약
    @Transactional
    public void reserveSeat(Long concertTimeId, String seatNumber,String userId) throws Exception {
        ConcertTime concertTime = concertTimeRepository.findById(concertTimeId)
                .orElseThrow(() -> new Exception("Concert time not found"));

        Optional<Seat> seatOptional = seatRepository.findByConcertTimeId(concertTimeId).stream()
                .filter(seat -> seat.getSeatNumber().equals(seatNumber))
                .findFirst();

        if (!seatOptional.isPresent()) {
            throw new Exception("Seat not found");
        }

        Seat seat = seatOptional.get();
        if (seat.isReserved()) {
            throw new Exception("Seat is already reserved");
        }

        seat.setReserved(true);
        concertTime.setSeatAmount(concertTime.getSeatAmount() - 1); // 좌석 수 감소

        Reservation reservation = new Reservation();
        reservation.setUserId(userId);
        reservation.setReservationTime(LocalDateTime.now());
        reservation.setConcertTime(concertTime);
        reservation.setSeat(seat);
        reservation.setStatus("CONFIRMED");

        seatRepository.save(seat);
        concertTimeRepository.save(concertTime);
        reservationRepository.save(reservation);
    }
}
