package com.bank.aml.messaging;

import com.bank.aml.dto.DeadLetterDto;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 死信队列查询。
 */
@Service
public class DeadLetterService {

    private final StringRedisTemplate redisTemplate;

    private final QueueProperties props;

    public DeadLetterService(StringRedisTemplate redisTemplate, QueueProperties props) {
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    public List<DeadLetterDto> list() {
        var records = redisTemplate.opsForStream().range(props.getDeadStream(), Range.<String>unbounded());
        if (records == null) {
            return List.of();
        }
        return records.stream().map(this::toDto).toList();
    }

    private DeadLetterDto toDto(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        try {
            return new DeadLetterDto(record.getId().getValue(), Long.parseLong(required(values, "caseId")),
                    required(values, "eventType"), Integer.parseInt(required(values, "executionVersion")),
                    required(values, "idempotencyKey"));
        }
        catch (NumberFormatException e) {
            throw new IllegalStateException("死信队列消息格式无效", e);
        }
    }

    private String required(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("死信队列消息格式无效");
        }
        return value.toString();
    }

}
