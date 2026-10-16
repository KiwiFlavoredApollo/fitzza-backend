package com.fitzza.community.votesupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOption;
import com.fitzza.community.domain.VoteOptionSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JevVoteSupportJudgeTest {

    private static final String BASE_URL = "https://jev.example.com";
    private static final String API_KEY = "test-jev-key";
    private static final double THRESHOLD = 0.6;

    private static final VoteOption SHIRT =
            option(100L, 1, VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, "오버핏 셔츠", 39000);
    private static final VoteOption PHOTO = option(101L, 2, VoteOptionSource.UPLOAD, VoteItemType.IMAGE, null, null);
    private static final List<VoteOption> OPTIONS = List.of(SHIRT, PHOTO);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void asksJevWithTheOptionLabelsAndReturnsTheChosenOption() {
        JevVoteSupportJudge judge = new JevVoteSupportJudge(builder, BASE_URL, API_KEY, THRESHOLD);
        server.expect(requestTo(BASE_URL + JevVoteSupportJudge.PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value(JevVoteSupportJudge.MODEL))
                .andExpect(jsonPath("$.state").value(containsString("댓글: 사진 쪽이 더 나아요")))
                .andExpect(jsonPath("$.state").value(containsString("어떤 게 나아요?")))
                .andExpect(jsonPath("$.questions.support.type").value("choice"))
                .andExpect(jsonPath("$.questions.support.criteria.A").value("1번째 선택지: 오버핏 셔츠 (39,000원)"))
                .andExpect(jsonPath("$.questions.support.criteria.B").value("2번째 선택지: 작성자가 올린 사진"))
                .andExpect(jsonPath("$.questions.support.criteria.none").exists())
                .andRespond(withSuccess(answer("B", 0.83), MediaType.APPLICATION_JSON));

        assertThat(judge.judge("어떤 게 나아요?", "사진 쪽이 더 나아요", OPTIONS)).contains(101L);
        server.verify();
    }

    @Test
    void anUnsureAnswerOrNoneLeavesTheCommentUnjudged() {
        JevVoteSupportJudge judge = new JevVoteSupportJudge(builder, BASE_URL, API_KEY, THRESHOLD);
        server.expect(requestTo(BASE_URL + JevVoteSupportJudge.PATH))
                .andRespond(withSuccess(answer("A", 0.41), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + JevVoteSupportJudge.PATH))
                .andRespond(withSuccess(answer("none", 0.97), MediaType.APPLICATION_JSON));

        assertThat(judge.judge("제목", "음 글쎄요 A?", OPTIONS)).isEmpty();
        assertThat(judge.judge("제목", "어디서 샀어요?", OPTIONS)).isEmpty();
        server.verify();
    }

    @Test
    void aFailingOrMalformedJevResponseLeavesTheCommentUnjudged() {
        JevVoteSupportJudge judge = new JevVoteSupportJudge(builder, BASE_URL, API_KEY, THRESHOLD);
        server.expect(requestTo(BASE_URL + JevVoteSupportJudge.PATH)).andRespond(withServerError());
        server.expect(requestTo(BASE_URL + JevVoteSupportJudge.PATH))
                .andRespond(withSuccess("{\"answers\":{}}", MediaType.APPLICATION_JSON));

        assertThat(judge.judge("제목", "A요", OPTIONS)).isEmpty();
        assertThat(judge.judge("제목", "A요", OPTIONS)).isEmpty();
        server.verify();
    }

    @Test
    void withoutAnApiKeyNothingIsSentToJev() {
        JevVoteSupportJudge judge = new JevVoteSupportJudge(builder, BASE_URL, " ", THRESHOLD);

        assertThat(judge.judge("제목", "A가 나아요", OPTIONS)).isEmpty();
        server.verify();
    }

    @Test
    void aComboWithoutANameIsDescribedAsAnAiCombo() {
        VoteOption combo = option(102L, 3, VoteOptionSource.AI_RECOMMEND, VoteItemType.COMBO, null, 89000);

        assertThat(JevVoteSupportJudge.describe(combo)).isEqualTo("3번째 선택지: AI 추천 코디 (89,000원)");
    }

    private static String answer(String choice, double confidence) {
        return "{\"answers\":{\"support\":{\"choice\":\"" + choice + "\",\"confidence\":" + confidence
                + ",\"probabilities\":{\"A\":0.1}}},\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}";
    }

    private static VoteOption option(
            Long id, int order, VoteOptionSource source, VoteItemType type, String name, Integer price) {
        VoteOption option = VoteOption.of(10L, order, source, type, type == VoteItemType.IMAGE ? null : "55",
                "https://cdn.example.com/" + id + ".png", name, price);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }
}
