package com.bank.aml.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 检索评测集生成器：从法规库条款程序化生成检索问题，标注标准答案 evidenceId。
 * <p>每条片段按"第X条 主题"生成自然语言检索问题，标准答案为该条款自身的证据 ID。
 */
@Component
public class RagEvalSetGenerator {

    private static final Pattern TITLE_PATTERN = Pattern.compile("(?:^|\\n)\\s*#+\\s*(第[一二三四五六七八九十百千0-9]+条[^\\n]*)");
    private static final int MAX_CASES = 40;

    private final JdbcTemplate pgJdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagEvalSetGenerator(@Qualifier("pgDataSource") DataSource pgDataSource) {
        this.pgJdbc = new JdbcTemplate(pgDataSource);
    }

    public record RagEvalCase(String question, String expectedEvidenceId, String expectedTitle) {
    }

    public List<RagEvalCase> generate() {
        List<RagEvalCase> cases = new ArrayList<>();
        String sql = "SELECT text, metadata::text FROM legal_docs ORDER BY text";
        pgJdbc.query(sql, rs -> {
            String text = rs.getString(1);
            String metadataJson = rs.getString(2);
            String evidenceId = "", title = "", article = "";
            try {
                JsonNode node = objectMapper.readTree(metadataJson);
                evidenceId = node.path("evidenceId").asText();
                title = node.path("title").asText();
                article = node.path("articleNumber").asText();
            } catch (Exception ignored) {
                // ignore
            }
            if (evidenceId.isEmpty() || text.length() < 30) {
                return;
            }
            String topic = extractTopic(text, article);
            if (topic.isEmpty()) {
                return; // 无明确条款主题的片段不纳入评测，保证问题语义有效
            }
            String question = "根据反洗钱相关法规，关于「" + topic + "」的具体监管要求是什么？";
            cases.add(new RagEvalCase(question, evidenceId, title));
        });
        return cases.size() <= MAX_CASES ? cases : cases.subList(0, MAX_CASES);
    }

    /** 从段落提取检索主题：优先条款标题，否则取正文首句（语义完整） */
    private String extractTopic(String text, String article) {
        Matcher m = TITLE_PATTERN.matcher(text);
        if (m.find()) {
            String heading = m.group(1).trim();
            String topic = heading.replaceFirst("第[一二三四五六七八九十百千0-9]+条", "")
                    .replaceFirst("[\\s:：、.。]*", "");
            if (topic.length() >= 2 && !topic.matches(".*[。；]")) {
                return topic;
            }
        }
        // 正文段：取去掉标题后的首句作为检索主题
        String clean = text.replaceAll("(?m)^#+\\s*", "").trim();
        String first = clean.split("[。；]")[0].trim();
        if (first.length() >= 6) {
            return first.length() <= 24 ? first : first.substring(0, 24);
        }
        return "";
    }
}
