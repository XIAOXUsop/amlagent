package com.bank.aml.tools;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.ShareholdingRecord;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工商股权工具：穿透查询股权结构与最终受益人（UBO）。
 */
@Component
public class CorporateTool {

    private final CustomerDataPort dataSource;

    public CorporateTool(CustomerDataPort dataSource) {
        this.dataSource = dataSource;
    }

    @Tool("穿透查询企业股权结构与最终受益人(UBO)，返回股东层级、关联公司、受益所有人")
    public String corporateProfile(@P("客户编号，如 C001") String customerId) {
        List<ShareholdingRecord> list = dataSource.shareholdingsOf(customerId);
        if (list.isEmpty()) {
            return "未查询到客户 " + customerId + " 的股权结构信息。";
        }
        String body = list.stream()
                .map(s -> String.format("- [%s] %s（%s），持股 %.0f%%", s.level(), s.holder(), s.holderType(),
                        s.ratio().movePointRight(2).doubleValue()))
                .collect(Collectors.joining("\n"));
        return "客户 " + customerId + " 股权穿透信息：\n" + body;
    }
}
