package com.bank.aml.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cookie 认证 + CSRF 完整链路安全测试（复用本机 Docker 的 MySQL/Redis/PGVector）。
 * <p>覆盖任务书 D10 的 MockMvc 安全矩阵：未认证 401、角色 403、Cookie 已认证但无 CSRF 403、
 * Cookie + 正确 CSRF 放行、login 豁免 CSRF、logout 需要 CSRF。
 * 运行：./mvnw test -Dgroups=integration
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@TestPropertySource(properties = "aml.rag.rerank.enabled=false")
class CsrfSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    @DisplayName("未认证访问业务接口返回 401")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/cases"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("query 参数 token 不被认证（JWT 仅从 Bearer/Cookie 读取）")
    void queryTokenIsNotAuthenticated() throws Exception {
        String token = tokenProvider.createToken("admin", "ADMIN");
        mockMvc.perform(get("/api/cases").queryParam("token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ANALYST 访问评测状态被 403 拒绝")
    void analystCannotAccessEvalStatus() throws Exception {
        Cookie token = login("analyst", "analyst123");

        mockMvc.perform(get("/api/eval/agent/status").cookie(token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER 重放死信被 403 拒绝")
    void reviewerCannotReplayDeadLetter() throws Exception {
        Auth auth = loginWithCsrf("reviewer", "reviewer123");

        mockMvc.perform(post("/api/queues/dead/999999/replay")
                        .cookie(auth.token())
                        .cookie(auth.xsrf())
                        .header("X-XSRF-TOKEN", auth.csrfValue()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cookie 已认证但写请求无 CSRF 返回 403")
    void authenticatedPostWithoutCsrfReturns403() throws Exception {
        Cookie token = login("admin", "admin123");

        mockMvc.perform(post("/api/cases")
                        .cookie(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"C001\",\"alertRule\":\"测试\",\"autoProcess\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cookie + 正确 CSRF 写请求被放行")
    void authenticatedPostWithCsrfSucceeds() throws Exception {
        Auth auth = loginWithCsrf("admin", "admin123");

        mockMvc.perform(post("/api/cases")
                        .cookie(auth.token())
                        .cookie(auth.xsrf())
                        .header("X-XSRF-TOKEN", auth.csrfValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"C001\",\"alertRule\":\"CSRF 链路测试\",\"autoProcess\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("登录接口豁免 CSRF")
    void loginWithoutCsrfAllowed() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("登出接口需要 CSRF（无 CSRF 返回 403）")
    void logoutWithoutCsrfReturns403() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isForbidden());
    }

    private Cookie login(String username, String password) throws Exception {
        return loginWithCsrf(username, password).token();
    }

    /** 登录签发 HttpOnly Cookie，再调用 /auth/csrf 强制生成可读的 XSRF-TOKEN Cookie */
    private Auth loginWithCsrf(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie token = login.getResponse().getCookie("aml_token");

        MvcResult csrf = mockMvc.perform(get("/api/auth/csrf").cookie(token))
                .andExpect(status().isOk())
                .andReturn();
        Cookie xsrf = csrf.getResponse().getCookie("XSRF-TOKEN");
        return new Auth(token, xsrf);
    }

    /** aml_token（HttpOnly 认证）+ XSRF-TOKEN（可读，写请求需原样回传 Cookie 与 X-XSRF-TOKEN header） */
    private record Auth(Cookie token, Cookie xsrf) {
        String csrfValue() {
            return xsrf == null ? null : xsrf.getValue();
        }
    }
}
