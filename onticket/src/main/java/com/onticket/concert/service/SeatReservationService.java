package com.onticket.concert.service;

import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Reservation;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.CalDto;
import com.onticket.concert.dto.ReservRequest;
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
import java.time.format.DateTimeFormatter;
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
    public void reserveSeat(String concertId, ReservRequest reservRequest) throws Exception {

        Long concertTimeId= reservRequest.getConcertTimeId();
        String username= reservRequest.getUsername();
        String seatNumber= reservRequest.getSeatNumber();

        ConcertTime concertTime = concertTimeRepository.findById(concertTimeId)
                .orElseThrow(() -> new Exception("해당 콘서트가 없습니다."));

        Optional<Seat> seatOptional = seatRepository.findByConcertTimeId(concertTimeId).stream()
                .filter(seat -> seat.getSeatNumber().equals(seatNumber))
                .findFirst();

        if (!seatOptional.isPresent()) {
            throw new Exception("존재하지 않는 좌석입니다.");
        }

        Seat seat = seatOptional.get();
        if (seat.isReserved()) {
            throw new Exception("이미 예약된 좌석입니다.");
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        //해당좌석 예약처리
        seat.setReserved(true);

        //전체 좌석수 감소
        concertTime.setSeatAmount(concertTime.getSeatAmount() - 1); // 좌석 수 감소

        Reservation reservation = new Reservation();
        reservation.setConcertId(concertId);
        reservation.setUsername(username);
        reservation.setCreatedAt(LocalDateTime.now());
        reservation.setConcertTime(LocalTime.parse(concertTime.getStartTime().format(formatter)));
        reservation.setConcertDate(concertTime.getDate());
        reservation.setSeat(seat);
        reservation.setStatus("예약완료");

        seatRepository.save(seat);
        concertTimeRepository.save(concertTime);
        reservationRepository.save(reservation);
    }

    //예약내역 조회(지난 날짜는 제외)
    public List<Reservation> getPersonalReservation(String username) throws Exception {
        Optional<List<Reservation>> reservationList=reservationRepository.findByUsername(username);
        if(!reservationList.isPresent()){
            throw new Exception("에약내역이 존재하지 않습니다.");
        }
        List<Reservation> printList=new ArrayList<>();
        for(Reservation reserv: reservationList.get()){
            LocalDate localDate = LocalDate.now();
            if(localDate.isBefore(reserv.getConcertDate())){
                continue;
            }
            printList.add(reserv);
        }
        return printList;
    }
}
