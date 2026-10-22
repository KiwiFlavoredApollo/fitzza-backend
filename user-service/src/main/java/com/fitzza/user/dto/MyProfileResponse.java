package com.fitzza.user.dto;

import com.fitzza.user.domain.Gender;
import com.fitzza.user.domain.User;
import com.fitzza.user.domain.UserBody;
import java.util.List;

// 계정 정보와 신체·취향 정보를 한 단계로 펼쳐서 내려준다(이슈 #44).
public record MyProfileResponse(
        String email,
        String nickname,
        Gender gender,
        Float height,
        Float weight,
        Integer shoeSize,
        String preferredFit,
        List<String> preferredStyles) {

    public static MyProfileResponse of(User user, UserBody body) {
        return new MyProfileResponse(
                user.getEmail(),
                user.getNickname(),
                body.getGender(),
                body.getHeight(),
                body.getWeight(),
                body.getShoeSize(),
                body.getPreferredFit(),
                body.getPreferredStyles());
    }
}
