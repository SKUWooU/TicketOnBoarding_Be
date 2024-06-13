package com.onticket.user.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoDto {
    private String nickName;
    private int code;
    private boolean valid;
}
