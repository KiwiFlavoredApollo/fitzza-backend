package com.fitzza.community.votesupport;

import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOption;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// TypeSafe Jev에 "이 댓글은 어느 선택지를 추천하나"를 보기 고르기(choice) 질문으로 묻는다.
// 보기는 선택지 라벨(A~D)과 none(특정 선택지를 추천하지 않음)이다. 고른 보기의 확신이 기준값 이상일 때만 그 선택지로 본다.
// API 키(TYPESAFE_API_KEY)가 비어 있으면 묻지 않고 모든 댓글을 "판정 없음"으로 둔다. 키는 로그에 남기지 않는다.
@Component
public class JevVoteSupportJudge implements VoteSupportJudge {

    static final String PATH = "/v1/systemone";
    static final String MODEL = "jev-latest";
    static final String QUESTION = "support";
    static final String NONE = "none";

    private static final Logger log = LoggerFactory.getLogger(JevVoteSupportJudge.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    // Jev는 한 요청에 수 초가 걸릴 수 있다. 판정은 별도 스레드에서 하므로 댓글 응답은 기다리지 않는다.
    private static final int READ_TIMEOUT_MILLIS = 15000;

    static final String INSTRUCTIONS = "패션 커뮤니티의 투표 글에 달린 댓글입니다. 댓글 작성자가 투표 선택지 중 하나를 "
            + "추천하거나 더 낫다고 말하는지 판단하세요. 'A가 나아요', '1번', '첫 번째', '왼쪽', 선택지의 상품명처럼 "
            + "한 선택지를 분명히 고르면 그 선택지의 라벨을 고르세요. 질문, 잡담, 둘 다 좋다는 말, 어느 선택지인지 "
            + "알 수 없는 말이면 none을 고르세요.";

    private final RestClient restClient;
    private final boolean enabled;
    private final double threshold;

    @Autowired
    public JevVoteSupportJudge(
            @Value("${fitzza.jev.base-url:https://api.typesafe.ai}") String baseUrl,
            @Value("${fitzza.jev.api-key:}") String apiKey,
            @Value("${fitzza.jev.support-threshold:0.6}") double threshold) {
        this(RestClient.builder().requestFactory(timeoutRequestFactory()), baseUrl, apiKey, threshold);
    }

    JevVoteSupportJudge(RestClient.Builder builder, String baseUrl, String apiKey, double threshold) {
        this.enabled = apiKey != null && !apiKey.isBlank();
        this.threshold = threshold;
        builder.baseUrl(baseUrl);
        if (enabled) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.strip());
        }
        this.restClient = builder.build();
    }

    @Override
    public Optional<Long> judge(String postTitle, String comment, List<VoteOption> options) {
        if (!enabled || options.isEmpty() || comment == null || comment.isBlank()) {
            return Optional.empty();
        }
        Map<String, Long> optionIdsByLabel = new LinkedHashMap<>();
        Map<String, String> criteria = new LinkedHashMap<>();
        for (VoteOption option : options) {
            optionIdsByLabel.put(option.getLabel(), option.getId());
            criteria.put(option.getLabel(), describe(option));
        }
        criteria.put(NONE, "특정 선택지를 추천하지 않음(질문, 잡담, 여러 선택지를 똑같이 좋다고 함, 판단할 수 없음)");

        Map<String, Object> body = Map.of(
                "model", MODEL,
                "state", state(postTitle, comment),
                "questions", Map.of(QUESTION, Map.of(
                        "type", "choice",
                        "instructions", INSTRUCTIONS,
                        "criteria", criteria)));
        try {
            JevResponse response = restClient
                    .post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JevResponse.class);
            Answer answer = response == null || response.answers() == null ? null : response.answers().get(QUESTION);
            if (answer == null || answer.choice() == null || answer.confidence() == null) {
                log.warn("Jev 응답에 판정이 없어 지지 선택지를 비워 둡니다.");
                return Optional.empty();
            }
            if (answer.confidence() < threshold) {
                return Optional.empty();
            }
            return Optional.ofNullable(optionIdsByLabel.get(answer.choice()));
        } catch (RestClientException exception) {
            log.warn("Jev 판정 실패, 지지 선택지를 비워 둡니다: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    static String state(String postTitle, String comment) {
        String title = postTitle == null || postTitle.isBlank() ? "" : "투표 글 제목: " + postTitle.strip() + "\n";
        return title + "댓글: " + comment.strip();
    }

    // 코디나 이름 없는 상품은 이름 대신 종류로 구분한다.
    static String describe(VoteOption option) {
        String name = option.getSnapshotName();
        if (name == null || name.isBlank()) {
            name = option.getItemType() == VoteItemType.COMBO ? "AI 추천 코디" : "상품";
        }
        String text = option.getDisplayOrder() + "번째 선택지: " + name.strip();
        Integer price = option.getSnapshotPrice();
        if (price != null) {
            text += " (" + NumberFormat.getIntegerInstance(Locale.KOREA).format(price) + "원)";
        }
        return text;
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
        return requestFactory;
    }

    record JevResponse(Map<String, Answer> answers) {
    }

    record Answer(String choice, Double confidence) {
    }
}
