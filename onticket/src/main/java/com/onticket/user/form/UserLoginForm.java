package com.onticket.user.form;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Data
public class UserLoginForm {
    @NotEmpty(message = "아이디를 입력하세요.")
    private String username;
    @NotEmpty(message = "비밀번호를 입력하세요.")
    private String password;
}
