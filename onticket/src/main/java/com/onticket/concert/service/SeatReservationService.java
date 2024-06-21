package com.onticket.concert.service;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Reservation;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.CalDto;
import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.dto.SeatDto;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.repository.SeatRepository;
import com.onticket.user.jwt.JwtUtil;
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
    private final JwtUtil jwtUtil;
    private final ConcertRepository concertRepository;

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
            seatDto.setSeatId(seat.getId());
            seatDto.setSeatNumber(seat.getSeatNumber());
            seatDto.setReserved(seat.isReserved());
            seatDtoList.add(seatDto);
        }
        return seatDtoList;
    }


    //좌석에약
    @Transactional
    public void reserveSeat(String username,String concertId, ReservRequest reservRequest) throws Exception {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        Long concertTimeId= reservRequest.getConcertTimeId();
        List<String> seatNumberList= reservRequest.getSeatNumberList();
        Concert concert = concertRepository.findByConcertId(concertId);
        ConcertTime concertTime = concertTimeRepository.findById(concertTimeId)
                .orElseThrow(() -> new Exception("해당 콘서트가 없습니다."));
        List<Seat> seatList = new ArrayList<>();
        List<Reservation> reservationList = new ArrayList<>();
        for(String seatNumber:seatNumberList){
            Optional<Seat> seatOptional = seatRepository.findByConcertTimeIdAndSeatNumberWithLock(concertTimeId, seatNumber);
            if (!seatOptional.isPresent()) {
                throw new Exception("존재하지 않는 좌석입니다.");
            }


            Seat seat = seatOptional.get();
            System.out.println(seat);

            if (seat.isReserved()) {
                throw new Exception("이미 예약된 좌석입니다.");
            }

            //해당좌석 예약처리
            seat.setReserved(true);
            seatList.add(seat);
            seatRepository.save(seat);

            //예약DB 추가
            Reservation reservation = new Reservation();
            reservation.setConcertId(concertId);
            reservation.setConcertName(concert.getConcertName());
            reservation.setPosterUrl(concert.getPosterUrl());
            reservation.setUsername(username);
            reservation.setCreatedAt(LocalDateTime.now());
            reservation.setConcertTimeId(concertTimeId);
            reservation.setConcertTime(LocalTime.parse(concertTime.getStartTime().format(formatter)));
            reservation.setConcertDate(concertTime.getDate());
            reservation.setSeat(seat);
            reservation.setSeatNumber(seatNumber);
            reservation.setStatus("결제완료");

            reservationList.add(reservation);
            reservationRepository.save(reservation);
        }



        //전체 좌석수 감소
        concertTime.setSeatAmount(concertTime.getSeatAmount() - seatNumberList.size()); // 좌석 수 감소
        concertTimeRepository.save(concertTime);
        seatRepository.saveAll(seatList);
        reservationRepository.saveAll(reservationList);
    }

    //예약내역 조회(지난 날짜는 제외)
    public List<Reservation> getPersonalReservation(String username) throws Exception {
        Optional<List<Reservation>> reservationList=reservationRepository.findByUsername(username);
        if(reservationList.isEmpty()){
            throw new Exception("에약내역이 존재하지 않습니다.");
        }

        return reservationList.get();
    }

    //관리자페이지-취소신청내약 조회
    public List<Reservation> getCancelList(){
        return reservationRepository.findByStatus("취소신청");
    }

    //에매 취소처리
    public void cancelReservation(Long reservationId) throws Exception {
        Optional<Reservation> reservation = reservationRepository.findById(reservationId);
        System.out.println(reservationId);
        if(reservation.isPresent()){
            Reservation reservation1 = reservation.get();
            Seat seat = seatRepository.findByConcertTimeAndSeatNumber(reservation1.getConcertTimeId(), reservation1.getSeatNumber());
            System.out.println(seat.isReserved());
            seat.setReserved(false);
            seatRepository.save(seat);
            reservation1.setStatus("취소완료");
            reservationRepository.save(reservation1);
            ConcertTime concertTime = concertTimeRepository.findById(reservation1.getConcertTimeId()).get();
            concertTime.setSeatAmount(concertTime.getSeatAmount() + 1);
            concertTimeRepository.save(concertTime);
            reservationRepository.save(reservation1);
        }
    }

}
