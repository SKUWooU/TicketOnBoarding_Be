CREATE TABLE concert (
    concert_id VARCHAR(255) NOT NULL,
    end_date DATE NULL,
    on_ticket_pick INT NOT NULL,
    start_date DATE NULL,
    concert_name VARCHAR(255) NULL,
    genre VARCHAR(255) NULL,
    poster_url VARCHAR(255) NULL,
    status VARCHAR(255) NULL,
    PRIMARY KEY (concert_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE place (
    place_id VARCHAR(255) NOT NULL,
    latitude DECIMAL(10, 7) NULL,
    longitude DECIMAL(10, 7) NULL,
    addr VARCHAR(255) NULL,
    gugun VARCHAR(255) NULL,
    place_name VARCHAR(255) NULL,
    sido VARCHAR(255) NULL,
    PRIMARY KEY (place_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE site_user (
    username VARCHAR(255) NOT NULL,
    code INT NOT NULL,
    createdate DATETIME(6) NULL,
    email VARCHAR(255) NULL,
    googleemail VARCHAR(255) NULL,
    naverid VARCHAR(255) NULL,
    nickname VARCHAR(255) NULL,
    password VARCHAR(255) NULL,
    phonenumber VARCHAR(255) NULL,
    PRIMARY KEY (username),
    UNIQUE KEY uk_site_user_phone_number (phonenumber)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE concert_detail (
    concert_id VARCHAR(255) NOT NULL,
    average_rating FLOAT NOT NULL,
    age VARCHAR(255) NULL,
    company VARCHAR(255) NULL,
    crew VARCHAR(255) NULL,
    performers VARCHAR(255) NULL,
    place VARCHAR(255) NULL,
    place_id VARCHAR(255) NULL,
    price VARCHAR(255) NULL,
    runtime VARCHAR(255) NULL,
    start_time VARCHAR(255) NULL,
    PRIMARY KEY (concert_id),
    CONSTRAINT fk_concert_detail_concert FOREIGN KEY (concert_id) REFERENCES concert (concert_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE concert_time (
    id BIGINT NOT NULL AUTO_INCREMENT,
    date DATE NULL,
    seat_amount INT NOT NULL,
    start_time TIME(6) NULL,
    seat_layout_version VARCHAR(50) NULL,
    concert_id VARCHAR(255) NULL,
    day_of_week VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_concert_time_concert FOREIGN KEY (concert_id) REFERENCES concert (concert_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refresh_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    token VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_token (token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_booking (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    username VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_username_idempotency_key (username, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE seat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    layout_row_order INT NULL,
    layout_seat_index INT NULL,
    layout_section_order INT NULL,
    reserved BIT NOT NULL,
    concert_time_id BIGINT NULL,
    held_until DATETIME(6) NULL,
    layout_row_label VARCHAR(20) NULL,
    layout_section_code VARCHAR(30) NULL,
    layout_section_name VARCHAR(50) NULL,
    held_by VARCHAR(100) NULL,
    seat_number VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seat_concert_time_number (concert_time_id, seat_number),
    KEY idx_seat_layout_section_position (concert_time_id, layout_section_code, layout_row_order, layout_seat_index),
    CONSTRAINT fk_seat_concert_time FOREIGN KEY (concert_time_id) REFERENCES concert_time (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_checkout (
    id BIGINT NOT NULL AUTO_INCREMENT,
    booking_id BIGINT NULL,
    canceled_at DATETIME(6) NULL,
    concert_time_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expected_amount BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    verification_deadline DATETIME(6) NULL,
    verification_started_at DATETIME(6) NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    verification_request_fingerprint VARCHAR(64) NULL,
    concert_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    merchant_uid VARCHAR(100) NOT NULL,
    username VARCHAR(100) NOT NULL,
    verification_idempotency_key VARCHAR(100) NULL,
    verification_payment_id VARCHAR(100) NULL,
    status ENUM('READY', 'PAYMENT_VERIFYING', 'PAYMENT_VERIFICATION_UNKNOWN', 'RESERVATION_CONFIRMED', 'CANCELED', 'EXPIRED') NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_merchant_uid (merchant_uid),
    UNIQUE KEY uk_checkout_username_idempotency_key (username, idempotency_key),
    UNIQUE KEY uk_checkout_hold_identity (username, request_fingerprint, expires_at),
    UNIQUE KEY uk_checkout_booking (booking_id),
    CONSTRAINT fk_checkout_booking FOREIGN KEY (booking_id) REFERENCES reservation_booking (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_checkout_request_key (
    id BIGINT NOT NULL AUTO_INCREMENT,
    checkout_id BIGINT NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    username VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_request_key_username_idempotency (username, idempotency_key),
    CONSTRAINT fk_checkout_request_key_checkout FOREIGN KEY (checkout_id) REFERENCES reservation_checkout (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_checkout_seat_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    active_seat_id BIGINT NULL,
    active_until DATETIME(6) NOT NULL,
    checkout_id BIGINT NOT NULL,
    original_hold_expires_at DATETIME(6) NOT NULL,
    released_at DATETIME(6) NULL,
    seat_id BIGINT NOT NULL,
    verification_lease_until DATETIME(6) NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_seat_assignment_checkout_seat (checkout_id, seat_id),
    UNIQUE KEY uk_checkout_seat_assignment_active_seat (active_seat_id),
    CONSTRAINT fk_checkout_assignment_checkout FOREIGN KEY (checkout_id) REFERENCES reservation_checkout (id),
    CONSTRAINT fk_checkout_assignment_seat FOREIGN KEY (seat_id) REFERENCES seat (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_payment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    approved_amount BIGINT NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    booking_id BIGINT NOT NULL,
    provider_payment_id VARCHAR(100) NOT NULL,
    username VARCHAR(100) NOT NULL,
    status ENUM('APPROVED', 'RESERVATION_CONFIRMED') NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_provider_payment_id (provider_payment_id),
    UNIQUE KEY uk_payment_booking (booking_id),
    CONSTRAINT fk_payment_booking FOREIGN KEY (booking_id) REFERENCES reservation_booking (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    concert_date DATE NULL,
    concert_time TIME(6) NULL,
    booking_id BIGINT NULL,
    concert_time_id BIGINT NULL,
    created_at DATETIME(6) NULL,
    seat_id BIGINT NULL,
    concert_id VARCHAR(255) NULL,
    concert_name VARCHAR(255) NULL,
    poster_url VARCHAR(255) NULL,
    seat_number VARCHAR(255) NULL,
    status VARCHAR(255) NULL,
    username VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_booking FOREIGN KEY (booking_id) REFERENCES reservation_booking (id),
    CONSTRAINT fk_reservation_seat FOREIGN KEY (seat_id) REFERENCES seat (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    star_count FLOAT NOT NULL,
    date DATETIME(6) NULL,
    author VARCHAR(255) NULL,
    concert_id VARCHAR(255) NULL,
    content TEXT NULL,
    nick_name VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_review_concert_detail FOREIGN KEY (concert_id) REFERENCES concert_detail (concert_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sty_urls (
    id BIGINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sty_urls_sty_url (
    sty_urls_id BIGINT NOT NULL,
    sty_url VARCHAR(255) NULL,
    CONSTRAINT fk_sty_urls_value FOREIGN KEY (sty_urls_id) REFERENCES sty_urls (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
