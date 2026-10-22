package com.fitzza.user.exception;

import com.fitzza.user.dto.DuplicateField;
import java.util.List;

// 이메일·닉네임 중복을 한 번에 알리기 위해 중복된 필드 목록을 함께 들고 다닌다.
public class DuplicateValueException extends UserApiException {

    private final List<DuplicateField> fields;

    public DuplicateValueException(List<DuplicateField> fields) {
        super(ErrorCode.DUPLICATE_VALUE);
        this.fields = List.copyOf(fields);
    }

    public List<DuplicateField> getFields() {
        return fields;
    }
}
