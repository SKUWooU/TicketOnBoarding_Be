package com.onticket.user.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Data
public class UserFindIdForm {

    @NotEmpty(message = "전화번호를 입력하세요.")
    private String phonenumber;

    @NotEmpty(message = "이메일을 입력하세요.")
    @Email
    private String email;
}
