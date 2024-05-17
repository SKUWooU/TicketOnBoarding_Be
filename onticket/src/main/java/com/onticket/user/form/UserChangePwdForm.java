package com.onticket.user.form;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Data
public class UserChangePwdForm {

    @NotEmpty
    private String phonenumber;

    @NotEmpty
    private String password1;

    @NotEmpty
    private String password2;
}
