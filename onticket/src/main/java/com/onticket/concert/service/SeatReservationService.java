package com.onticket.concert.service;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Reservation;
import com.onticket.concert.domain.ReservationStatus;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.domain.SeatAvailability;
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
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final Clock clock;

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
        LocalDateTime now = LocalDateTime.now(clock);
        for(Seat seat:seatList){
            SeatAvailability availability = seat.availabilityAt(now);
            SeatDto seatDto=new SeatDto();
            seatDto.setSeatId(seat.getId());
            seatDto.setSeatNumber(seat.getSeatNumber());
            seatDto.setReserved(availability != SeatAvailability.AVAILABLE);
            seatDto.setAvailability(availability);
            seatDto.setHoldExpiresAt(
                    availability == SeatAvailability.HELD ? seat.getHeldUntil() : null
            );
            seatDtoList.add(seatDto);
        }
        return seatDtoList;
    }


    //좌석에약
    @Transactional(rollbackFor = Exception.class)
    public void reserveSeat(String username,String concertId, ReservRequest reservRequest) throws Exception {
        reserveSeat(username, concertId, reservRequest, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reserveSeat(
            String username,
            String concertId,
            ReservRequest reservRequest,
            Booking booking
    ) throws Exception {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        List<String> seatNumberList = ReservationRequestCanonicalizer.canonicalSeatNumbers(reservRequest);
        Long concertTimeId= reservRequest.getConcertTimeId();
        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new Exception("존재하지 않는 공연입니다."));
        ConcertTime concertTime = concertTimeRepository.findById(concertTimeId)
                .orElseThrow(() -> new Exception("해당 콘서트가 없습니다."));
        List<Seat> seatList = new ArrayList<>();
        List<Reservation> reservationList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(clock);
        for(String seatNumber:seatNumberList){
            Optional<Seat> seatOptional = seatRepository.findByConcertTimeIdAndSeatNumberWithLock(concertTimeId, seatNumber);
            if (!seatOptional.isPresent()) {
                throw new Exception("존재하지 않는 좌석입니다.");
            }


            Seat seat = seatOptional.get();

            if (seat.isReserved()) {
                throw new SeatReservationConflictException("이미 예약된 좌석입니다.");
            }
            seat.clearExpiredHold(now);
            if (seat.isHeldAt(now) && !seat.isHeldBy(username, now)) {
                throw new SeatHoldConflictException("다른 사용자가 임시 점유한 좌석입니다.");
            }

            //해당좌석 예약처리
            seat.markReserved();
            seatList.add(seat);
            seatRepository.save(seat);

            //예약DB 추가
            Reservation reservation = new Reservation();
            reservation.setConcertId(concertId);
            reservation.setConcertName(concert.getConcertName());
            reservation.setPosterUrl(concert.getPosterUrl());
            reservation.setUsername(username);
            reservation.setCreatedAt(now);
            reservation.setConcertTimeId(concertTimeId);
            reservation.setConcertTime(LocalTime.parse(concertTime.getStartTime().format(formatter)));
            reservation.setConcertDate(concertTime.getDate());
            reservation.setSeat(seat);
            reservation.setSeatNumber(seatNumber);
            reservation.markPaymentCompleted();
            reservation.setBooking(booking);

            reservationList.add(reservation);
            reservationRepository.save(reservation);
        }
        seatRepository.saveAll(seatList);
        reservationRepository.saveAll(reservationList);

        int updatedConcertTimes = concertTimeRepository.decreaseSeatAmountIfAvailable(
                concertTimeId,
                seatNumberList.size()
        );
        if (updatedConcertTimes != 1) {
            throw new SeatReservationConflictException("잔여 좌석이 부족합니다.");
        }
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
        return reservationRepository.findByStatus(ReservationStatus.CANCELLATION_REQUESTED);
    }

    @Transactional(rollbackFor = Exception.class)
    public void requestCancellation(String username, Long reservationId) {
        Reservation reservation = reservationRepository.findByIdWithLock(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("해당하는 예약이 없습니다."));

        if (!Objects.equals(reservation.getUsername(), username)) {
            throw new IllegalArgumentException("예약정보와 다른 사용자입니다.");
        }
        reservation.requestCancellation();
    }

    //예매 취소처리
    @Transactional(rollbackFor = Exception.class)
    public void cancelReservation(Long reservationId) throws Exception {
        Reservation reservation = reservationRepository.findByIdWithLock(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("해당하는 예약이 없습니다."));

        if (!reservation.completeCancellation()) {
            return;
        }

        Seat seat = reservation.getSeat();
        if (seat == null || !seat.isReserved()) {
            throw new IllegalStateException("예약 좌석 상태가 올바르지 않습니다.");
        }

        seat.markAvailable();
        int updatedConcertTimes = concertTimeRepository.increaseSeatAmount(reservation.getConcertTimeId());
        if (updatedConcertTimes != 1) {
            throw new IllegalStateException("공연 회차의 잔여 좌석을 복구할 수 없습니다.");
        }
    }

}
