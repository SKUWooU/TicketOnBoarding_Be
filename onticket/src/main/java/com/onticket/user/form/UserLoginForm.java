package com.onticket.user.form;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@RequiredArgsConstructor
@Data
public class UserLoginForm {
    @NotEmpty(message = "아이디를 입력하세요.")
    @JsonProperty("username")
    private String username;

    @NotEmpty(message = "비밀번호를 입력하세요.")
    @JsonProperty("password")
    private String password;
}
