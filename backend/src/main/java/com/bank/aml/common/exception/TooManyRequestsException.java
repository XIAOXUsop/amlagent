package com.bank.aml.common.exception;

/**
 * 登录/鉴权类请求触发速率限制：短时间内失败次数过多，返回 429 并要求稍后重试。
 * 用于缓解暴力破解与批量撞库，不暴露具体账号是否存在。
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
