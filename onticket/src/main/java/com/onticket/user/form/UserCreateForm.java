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
    @NotEmpty(message = "아이디는 필수")
    private String username;

    @NotEmpty(message = "비밀번호는 필수")
    private String password1;

    @NotEmpty(message = "비밀번호확인은 필수")
    private String password2;

    @NotEmpty(message = "이메일은 필수")
    @Email
    private String email;

    @NotEmpty(message = "이름은 필수")
    private String nickname;

    @NotEmpty(message = "전화번호는 필수")
    private String phonenumber;

    @NotEmpty(message = "인증번호는 필수")
    private String smscode;

}