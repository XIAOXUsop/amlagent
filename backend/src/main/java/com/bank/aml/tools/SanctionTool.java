package com.bank.aml.tools;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.SanctionRecord;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 制裁黑名单工具：检索 OFAC / 国内制裁名单，返回命中条目与风险等级。
 */
@Component
public class SanctionTool {

    private final CustomerDataPort dataSource;

    public SanctionTool(CustomerDataPort dataSource) {
        this.dataSource = dataSource;
    }

    @Tool("检索制裁黑名单（OFAC / 国内制裁名单），按客户姓名与证件号匹配，返回命中条目、名单类型与风险等级")
    public String checkSanctions(@P("客户姓名") String customerName, @P("客户证件号") String idCard) {
        List<SanctionRecord> hits = new ArrayList<>(dataSource.searchSanctions(customerName));
        if (idCard != null && !idCard.isBlank()) {
            hits.addAll(dataSource.searchSanctions(idCard));
        }
        // 去重
        List<SanctionRecord> distinct = hits.stream().distinct().toList();

        if (distinct.isEmpty()) {
            return "未命中制裁黑名单（OFAC / 国内名单）。";
        }
        String body = distinct.stream()
                .map(s -> String.format("- 命中[%s]：%s（风险等级：%d级），%s", s.listType(), s.name(), s.severity(), s.detail()))
                .collect(Collectors.joining("\n"));
        return "黑名单命中结果：\n" + body + "\n注意：命中一级制裁名单必须强制标记为高危险并转人工处理。";
    }
}
