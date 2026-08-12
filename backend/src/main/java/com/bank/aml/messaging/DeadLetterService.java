package com.bank.aml.messaging;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public List<Map<String, String>> list() {
        var records = redisTemplate.opsForStream().range(props.getDeadStream(), Range.<String>unbounded());
        if (records == null) {
            return List.of();
        }
        return records.stream().map(rec -> {
            Map<String, String> m = new HashMap<>();
            rec.getValue().forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
            return m;
        }).toList();
    }
}
