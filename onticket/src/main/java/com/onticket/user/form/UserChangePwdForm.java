package com.onticket.user.form;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Data
public class UserChangePwdForm {

    @NotEmpty(message = "전화번호를 보내주세요.")
    private String phonenumber;

    @NotEmpty(message = "비밀번호1을 입력하세요")
    private String password1;

    @NotEmpty(message = "비밀번호2를 입력하세요")
    private String password2;
}
