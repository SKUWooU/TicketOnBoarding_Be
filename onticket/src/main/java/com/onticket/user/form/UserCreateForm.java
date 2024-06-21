package com.onticket.user.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.SplittableRandom;

@AllArgsConstructor
@Data
public class UserCreateForm {
    @Size(min = 3, max = 25)
    @NotEmpty(message = "아이디를 입력하세요.")
    private String username;

    @NotEmpty(message = "비밀번호를 입력하세요.")
    private String password1;

    @NotEmpty(message = "비밀번호를 입력하세요.")
    private String password2;

    @NotEmpty(message = "이메일을 입력하세요.")
    @Email
    private String email;

    @NotEmpty(message = "이름을 입력하세요.")
    private String nickname;

    @NotEmpty(message = "전화번호를 입력하세요.")
    private String phonenumber;

    @NotEmpty(message = "인증번호를 입력하세요.")
    private String smscode;

}