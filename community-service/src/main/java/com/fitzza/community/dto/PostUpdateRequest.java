package com.fitzza.community.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.VoteOption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

// 보내지 않은 항목은 그대로 둔다.
// options는 투표글에서만 보낼 수 있고, 보내면 선택지를 통째로 바꾼다(투표가 한 표라도 있으면 거절). null은 받지 않는다.
public class PostUpdateRequest {

    private static final String HAS_TEXT = "(?s).*\\S.*";

    @Size(max = Post.TITLE_MAX_LENGTH, message = "제목은 100자 이하로 입력해주세요.")
    @Pattern(regexp = HAS_TEXT, message = "제목을 입력해주세요.")
    private String title;

    @Size(max = Post.CONTENT_MAX_LENGTH, message = "내용은 5000자 이하로 입력해주세요.")
    @Pattern(regexp = HAS_TEXT, message = "내용을 입력해주세요.")
    private String content;

    private PostCategory category;

    @Size(
            min = VoteOption.MIN_OPTIONS,
            max = VoteOption.MAX_OPTIONS,
            message = "투표 선택지는 2~4개여야 합니다.")
    private List<@NotNull(message = "투표 선택지를 확인해주세요.") @Valid VoteOptionRequest> options;

    // "보내지 않음"과 "null로 보냄"을 구분하려고 setter가 불렸는지 기록한다.
    private boolean optionsSent;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public PostCategory getCategory() {
        return category;
    }

    public void setCategory(PostCategory category) {
        this.category = category;
    }

    public List<VoteOptionRequest> getOptions() {
        return options;
    }

    public void setOptions(List<VoteOptionRequest> options) {
        this.options = options;
        this.optionsSent = true;
    }

    // options를 null로 보내면 "보내지 않음"과 구분되지 않으므로 400으로 거절한다.
    @JsonIgnore
    @AssertTrue(message = "투표 선택지를 확인해주세요.")
    public boolean isOptionsNotNullWhenSent() {
        return !optionsSent || options != null;
    }
}
