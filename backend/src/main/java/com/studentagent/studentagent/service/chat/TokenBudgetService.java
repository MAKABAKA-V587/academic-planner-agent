package com.studentagent.studentagent.service.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * LLM 成本控制：按用户按日统计 token 用量（Redis Hash），超限后当日拒绝新的 LLM 调用。
 *
 * 设计要点：
 * - 计数维度：userId + 自然日（yyyyMMdd），输入/输出 token 分别累加（Hash 的 in/out 字段）
 * - 存储结构：token:usage:{userId}:{yyyyMMdd}，TTL 48 小时自动清理，不产生长期残留
 * - 失败策略：Redis 不可用时记账/查询静默降级（返回 -1 视为未知，不拦截对话），
 *   成本控制绝不能阻断主链路
 * - 记账点：ChatLlmClient 阻塞调用累计（drainUsage）、ChatStreamOrchestrator 流式回调、
 *   ChatService.generateTitleAsync（独立 LLM 调用），见各接入处
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBudgetService {

    private final StringRedisTemplate redisTemplate;

    /** 总开关：关闭后不做限额检查也不记账 */
    @Value("${token-budget.enabled:true}")
    private boolean enabled;

    /** 单用户单日 token 总量（输入+输出）限额 */
    @Value("${token-budget.daily-limit:200000}")
    private long dailyLimit;

    public static final String KEY_PREFIX = "token:usage:";
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 用量 key 保留 48 小时：跨天后不再写入，最迟两天后自动过期清理 */
    private static final long TTL_SECONDS = 48 * 3600;

    /** 单次 LLM 调用（含工具循环多轮）累计的 token 用量 */
    public record Usage(long inputTokens, long outputTokens) {
        public long total() {
            return inputTokens + outputTokens;
        }

        /** 从 langchain4j TokenUsage 转换（计数字段可为 null，统一归零） */
        public static Usage fromChat(dev.langchain4j.model.output.TokenUsage u) {
            if (u == null) return new Usage(0, 0);
            return new Usage(
                    u.inputTokenCount() != null ? u.inputTokenCount() : 0,
                    u.outputTokenCount() != null ? u.outputTokenCount() : 0);
        }
    }

    /** 是否启用成本控制 */
    public boolean enabled() {
        return enabled;
    }

    /** 单用户单日 token 限额 */
    public long dailyLimit() {
        return dailyLimit;
    }

    /**
     * 记账：把一次调用的输入/输出 token 累加到今日用量。
     * 任何异常只告警不抛出——记账失败不影响对话。
     */
    public void recordUsage(Long userId, Usage usage) {
        if (!enabled || userId == null || usage == null || usage.total() <= 0) {
            return;
        }
        try {
            String key = todayKey(userId);
            if (usage.inputTokens() > 0) {
                redisTemplate.opsForHash().increment(key, "in", usage.inputTokens());
            }
            if (usage.outputTokens() > 0) {
                redisTemplate.opsForHash().increment(key, "out", usage.outputTokens());
            }
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("用户{}今日token记账 +in{}/+out{}", userId, usage.inputTokens(), usage.outputTokens());
        } catch (Exception e) {
            log.warn("用户{}token记账失败(不影响对话): {}", userId, e.getMessage());
        }
    }

    /**
     * 今日已用总量（输入+输出）。Redis 失败返回 -1（未知），调用方应视为未超限。
     */
    public long getTodayUsage(Long userId) {
        if (!enabled || userId == null) {
            return -1;
        }
        try {
            Object in = redisTemplate.opsForHash().get(todayKey(userId), "in");
            Object out = redisTemplate.opsForHash().get(todayKey(userId), "out");
            long total = 0;
            if (in != null) total += Long.parseLong(in.toString());
            if (out != null) total += Long.parseLong(out.toString());
            return total;
        } catch (Exception e) {
            log.warn("用户{}查询token用量失败(视为未超限): {}", userId, e.getMessage());
            return -1;
        }
    }

    /**
     * 是否已超今日限额。关闭/未知(Redis故障)/userId缺失一律返回 false（不拦截）。
     */
    public boolean exceeded(Long userId) {
        if (!enabled || userId == null) {
            return false;
        }
        long used = getTodayUsage(userId);
        return used >= 0 && used >= dailyLimit;
    }

    private String todayKey(Long userId) {
        return KEY_PREFIX + userId + ":" + LocalDate.now().format(DAY_FORMAT);
    }
}
