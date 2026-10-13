package com.fitzza.user.dto;

import com.fitzza.user.domain.Gender;
import com.fitzza.user.domain.UserBody;
import java.util.List;

public record UserBodyResponse(
        Gender gender,
        Float height,
        Float weight,
        Integer shoeSize,
        String preferredFit,
        List<String> preferredStyles) {

    public static UserBodyResponse from(UserBody body) {
        return new UserBodyResponse(
                body.getGender(),
                body.getHeight(),
                body.getWeight(),
                body.getShoeSize(),
                body.getPreferredFit(),
                body.getPreferredStyles());
    }
}
