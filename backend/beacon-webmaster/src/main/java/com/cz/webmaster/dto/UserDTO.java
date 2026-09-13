package com.cz.webmaster.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * @author cz
 * @description
 */
@Data
public class UserDTO {

    @NotBlank
    private String username;
    @NotBlank
    private String password;
    @NotBlank
    private String captcha;
    @NotBlank
    private String uuid;

    private Boolean rememberMe = false;


}
