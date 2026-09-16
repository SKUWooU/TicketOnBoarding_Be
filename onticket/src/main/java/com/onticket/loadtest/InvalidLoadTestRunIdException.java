package com.onticket.loadtest;

public class InvalidLoadTestRunIdException extends IllegalArgumentException {

    public InvalidLoadTestRunIdException() {
        super("loadtest runId는 영문·숫자·하이픈 1~32자여야 합니다.");
    }
}
