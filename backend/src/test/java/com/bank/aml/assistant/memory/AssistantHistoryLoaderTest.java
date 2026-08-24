package com.bank.aml.assistant.memory;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantResultType;
import com.bank.aml.assistant.persistence.entity.AssistantMessageEntity;
import com.bank.aml.assistant.persistence.repository.AssistantMessageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantHistoryLoaderTest {
    @Test
    void loadsOnlyCompletedPairsBeforeCurrentMessage() {
        AssistantMessageRepository repository = mock(AssistantMessageRepository.class);
        AssistantProperties properties = new AssistantProperties();
        AssistantMessageEntity oldUser = AssistantMessageEntity.user("c", 1, "old", "旧问题");
        AssistantMessageEntity oldAnswer = AssistantMessageEntity.assistantPlaceholder("c", 2);
        oldAnswer.complete("旧回答", AssistantResultType.ANSWERED);
        AssistantMessageEntity failedUser = AssistantMessageEntity.user("c", 3, "failed", "失败问题");
        AssistantMessageEntity failedAnswer = AssistantMessageEntity.assistantPlaceholder("c", 4);
        failedAnswer.fail("失败", AssistantResultType.MODEL_UNAVAILABLE);
        AssistantMessageEntity current = AssistantMessageEntity.user("c", 5, "current", "当前问题");
        when(repository.findTop100ByConversationIdOrderBySequenceNoAsc("c"))
                .thenReturn(List.of(oldUser, oldAnswer, failedUser, failedAnswer, current));

        var memory = new AssistantHistoryLoader(repository, properties).load("c", current.getId());

        assertThat(memory.messages()).hasSize(2);
        assertThat(memory.messages().toString()).contains("旧问题", "旧回答")
                .doesNotContain("失败问题", "当前问题");
    }
}
